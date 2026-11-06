(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.gl-canvas-kernel
  (:require
    [taoensso.timbre :as log]
    [top.kzre.krro.core.util.channel :refer [<| >| chan close!]]
    [top.kzre.krro.core.util.promise :as promise :refer [plet>]]
    [top.kzre.krro.core.window :as win]
    [top.kzre.krro.plugin.modeling.core.viewport.core :as core.viewport]
    [top.kzre.krro.plugin.modeling.editor.javafx.canvas.protocol :as protocol]
    [top.kzre.krro.plugin.painting.core.project.canvas :as pc]
    [top.kzre.krro.plugin.painting.editor.javafx.input.pointer :as pointer]
    [top.kzre.krro.plugin.painting.editor.javafx.input.protocol :as input]
    [top.kzre.krro.plugin.painting.editor.javafx.graph :as javafx.graph]
    [top.kzre.krro.plugin.painting.editor.javafx.glfx.core :as glfx])
  (:import
   (com.huskerdev.grapl.gl GLContext GLProfile)
   (com.huskerdev.openglfx.canvas GLCanvas GLCanvas$Builder GLCanvas$Builder$ContextDescription$New)
   (com.huskerdev.openglfx.canvas.events GLRenderEvent)
   (com.huskerdev.openglfx.lwjgl LWJGLExecutor)
   (java.util.function Consumer)
   (javafx.application Platform)
   (javafx.scene.canvas Canvas)
   (javafx.scene.layout Region StackPane)
   (javafx.scene Node)
   (top.kzre.krro.core.util SerialExecutor)
   (top.kzre.krro.plugin.modeling.core.project K3DRuntime)
   (top.kzre.krro.plugin.modeling.core.schedule Session)
   (top.kzre.krro.plugin.painting.editor.javafx.glfx GLRuntime GLSyncTiledTextureBlitter)
   (top.kzre.krro.util.math KMath)))


(defn- make-state []
  (atom {:scene-id nil
         :renderer-id nil
         :session nil
         :viewport nil
         :closed false}))

(defn setup-viewport!
  [{:keys [view-mat4 proj-mat4 view-proj-mat4
           state-atom]
    :as kernel} viewport]
  (let [aspect (protocol/view-aspect kernel)]
    (when (pos? aspect)
      (when-let [session (:session @state-atom)]
        (let [viewport (-> viewport
                           (assoc :aspect aspect))
              view-mat4 (core.viewport/viewport->view-mat4 viewport view-mat4)
              proj-mat4 (core.viewport/viewport->proj-mat4 viewport proj-mat4)
              view-proj-mat4 (KMath/mat4Mul proj-mat4 view-mat4 view-proj-mat4)]
          (.setViewProjection session view-proj-mat4))))))

(defn- setup-view-size!
  [{:keys [state-atom fx-container] :as kernel}]
  (when-let [session (:session @state-atom)]
    (let [w (or (.getWidth fx-container) 0)
          h (or (.getHeight fx-container) 0)]
      (.setViewSize session w h)
      (when-let [viewport (:viewport @state-atom)]
        (setup-viewport! kernel viewport)))))

(defn- closed? [{:keys [state-atom] :as _kernel}]
  (:closed @state-atom))

(defn- blit! [{:keys [gl-runtime gl-canvas sync-blitter repaint-chan] :as kernel} canvas]
  (when-not (closed? kernel)
    (let [^SerialExecutor gl-exec (.getExecutor ^GLRuntime gl-runtime)
          w (.getWidth gl-canvas)
          h (.getHeight gl-canvas)]
      (when (and (pos? w) (pos? h))
        (plet>
          [_
           (promise/from-completable-future
             (.submit
               gl-exec
               ^Callable
               (fn []
                 ;; 帧提交 + fence 全部封装在 send 内部；canvas 为空或已关闭时跳过
                 (when (and canvas (not (.isClosed canvas)))
                   (.send ^GLSyncTiledTextureBlitter sync-blitter
                          canvas w h)))))]
          (>| repaint-chan :dirty)
          (Platform/runLater
            (reify Runnable
              (run [_]
                (when (<| repaint-chan)
                  (.repaint gl-canvas))))))))))

(defn- setup-session!
  [{:keys [state-atom] :as kernel}
   & {:keys [scene-id renderer-id]}]
  (let [state @state-atom
        scene-id (or scene-id (:scene-id state))
        renderer-id  (or renderer-id (:renderer-id state))]
    (swap! state-atom assoc
           :scene-id scene-id
           :renderer-id renderer-id)
    (when (and scene-id renderer-id)
      (when-let [old-session (:session state)]
        ;; 关闭旧渲染会话
        (.close old-session))
      (let [scheduler (K3DRuntime/getSceneScheduler scene-id)
            new-session (.openSession scheduler renderer-id)
            view-chan  (.viewChannel new-session)]
        (swap! state-atom assoc :session new-session)
        (.setOnDirty new-session
                     (reify Runnable
                       (run [_]
                         (when-let [canvas (<| view-chan)]
                           (Platform/runLater
                             (reify Runnable
                               (run [_]
                                 (blit! kernel canvas))))))))))))


(defrecord GLCanvasKernalImpl
  [;; 显示相关
   ^GLCanvas gl-canvas
   ^Canvas overlay-canvas
   ^StackPane fx-container
   ^GLSyncTiledTextureBlitter sync-blitter

   ;; 合成相关
   ^GLRuntime gl-runtime
   repaint-chan
   view-mat4
   proj-mat4
   view-proj-mat4
   ;; 业务相关
   on-event
   mouse-input
   ;; 状态
   state-atom]

  protocol/ICanvasKernel
  (set-scene! [this scene-id]
    (when-not (closed? this)
      (setup-session! this :scene-id scene-id))
    this)
  (set-renderer! [this renderer-id]
    (when-not (closed? this)
      (setup-session! this :renderer-id renderer-id))
    this)
  (set-viewport! [this viewport]
    (when-not (closed? this)
      (swap! state-atom assoc :viewport viewport)
      (setup-viewport! this viewport)))

  (overlay-gc [_]
    (javafx.graph/make-javafx-graphics
      (.getGraphicsContext2D overlay-canvas)))
  (view-height [_] (.getHeight fx-container))
  (view-width [_] (.getWidth fx-container))
  (view-aspect [_]
    (let [w (.getWidth fx-container)
          h (.getHeight fx-container)]
     (if (pos? h) (double (/ w h)) 0) ))
  (close [_]
    (let [[old _] (swap-vals! state-atom assoc :closed true)]
      (when-not (:closed old)
        (let [{:keys [session]} old]
          (doseq [step [;; 立刻关生产者，不再产出新数据
                        (when mouse-input #(input/stop! mouse-input))

                        ;; 消费者按消费流逆序关闭 repaint->blitter
                        #(close! repaint-chan)
                        (when sync-blitter
                          #(try
                             ;;
                             (.close ^GLSyncTiledTextureBlitter sync-blitter)
                             (catch Throwable t
                               (log/warn t "sync-blitter close failed"))))
                        (when session #(.close ^Session session))
                        ;; 最后关上下文
                        (when gl-canvas #(.dispose ^GLCanvas gl-canvas))]]
            (when step
              (try
                (step)
                (catch Throwable t
                  (log/error t "kernel close step failed"))))))))))

;; ═══════════════════════════════════════════════
;; GLCanvas 创建
;; ═══════════════════════════════════════════════

(defn- make-gl-canvas
  "创建与离屏 runtime 共享的 GLCanvas。fps=0——手动 repaint。"
  ^GLCanvas [^GLRuntime gl-runtime]
  (let [^GLContext ctx (.getContext gl-runtime)]
    (-> (GLCanvas$Builder.)
        (.setExecutor LWJGLExecutor/LWJGL_MODULE)
        (.setFps (double 0))
        (.setContextDescription
          (-> (GLCanvas$Builder$ContextDescription$New.)
              (.setShareWith ctx)
              (.setProfile GLProfile/CORE)))
        (.build))))

(defn- build-layout
  [^GLRuntime gl-runtime]
  (let [fx-stack     (StackPane.)
        ^GLCanvas gl-canvas (make-gl-canvas gl-runtime)
        overlay      (Canvas.)]
    (.bind (.widthProperty  overlay) (.widthProperty  fx-stack))
    (.bind (.heightProperty overlay) (.heightProperty fx-stack))

    (doto fx-stack
      (.setMinWidth 0)
      (.setMinHeight 0))

    (doto (.getChildren fx-stack)
      (.add gl-canvas)
      (.add overlay))

    {:container      fx-stack
     :gl-canvas      gl-canvas
     :overlay-canvas overlay}))

(defn build-kernel
  [^GLRuntime gl-runtime
   {:keys [on-event]}]
  {:pre [(some? gl-runtime)
         (some? on-event)]}
  (let [{:keys [container gl-canvas overlay-canvas]}
        (build-layout gl-runtime)

        sync-blitter   (GLSyncTiledTextureBlitter/create pc/global-tile-size)
        mouse-input    (pointer/make-pointer-input
                         container
                         on-event)

        kernel
        (map->GLCanvasKernalImpl
          {:gl-canvas      gl-canvas
           :overlay-canvas overlay-canvas
           :fx-container   container
           :sync-blitter   sync-blitter
           :gl-runtime     gl-runtime
           :view-mat4 (float-array 16)
           :proj-mat4 (float-array 16)
           :view-proj-mat4 (float-array 16)
           :repaint-chan   (chan 1)
           :on-event       on-event
           :mouse-input    mouse-input
           :state-atom     (make-state)})]

    ;; ── onRenderEvent：GLCanvas 的 GL 线程，消费最新一帧 ──
    (.addOnRenderEvent
      gl-canvas
      (reify Consumer
        (accept [_ event]
          (.poll ^GLSyncTiledTextureBlitter sync-blitter ^GLRenderEvent event))))

    ;; ── onReshapeEvent：surface 尺寸变化，触发一次重渲染 ──
    (.addOnReshapeEvent
      gl-canvas
      (reify Consumer
        (accept [_ _]
          (setup-view-size! kernel))))

    (input/start! mouse-input)

    {:container container
     :kernel    kernel}))