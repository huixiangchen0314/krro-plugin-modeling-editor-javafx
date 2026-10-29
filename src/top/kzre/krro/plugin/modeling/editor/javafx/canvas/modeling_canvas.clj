(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.modeling-canvas
  (:require
    [top.kzre.krro.canvas.raster.core :as raster]
    [top.kzre.krro.plugin.modeling.editor.javafx.canvas.mesh :as mesh]
    [top.kzre.krro.plugin.painting.core.changes.viewport :as viewport]
    [top.kzre.krro.plugin.painting.core.project.canvas :as pc]
    [top.kzre.krro.plugin.painting.core.viewport :as vp]
    [top.kzre.krro.plugin.painting.editor.javafx.canvas.protocol :as proto]
    [top.kzre.krro.ui.javafx.core :refer [make-component]])
  (:import
    [javafx.application Platform]
    [javafx.beans InvalidationListener]
    [javafx.scene.layout StackPane]
    [java.util Collections]
    [java.util.function Consumer]
    (top.kzre.krro.canvas.gl.resource GLProgramCache PixelFormat)
    (top.kzre.krro.d3.renderer.studio.gl GLRenderer GLStudioRenderContext)
    (top.kzre.krro.d3.renderer.studio.pipeline StudioPipeline RenderRequest)
    (top.kzre.krro.plugin.painting.editor.javafx.glfx GLRuntime)
    (top.kzre.krro.util.tile TiledCanvas)))

(def ^:private kernal-fx-prop-key ::kernal)

;; ═══════════════════════════════════════════════
;; Context
;; ═══════════════════════════════════════════════

(defn create-context
  "创建 GLStudioRenderContext + 其 program cache。
   返回 {:ctx :programs}——cleanup 时两者都要关。"
  [^GLRuntime gl-runtime]
  (let [gl-thread (.getExecutor gl-runtime)
        programs  (GLProgramCache. gl-thread)
        ctx       (GLStudioRenderContext.
                    gl-thread
                    pc/global-tile-size
                    PixelFormat/RGBA8
                    programs)]
    {:ctx ctx :programs programs}))

;; ═══════════════════════════════════════════════
;; Layer 包装
;; ═══════════════════════════════════════════════

(defn wrap-layer [^TiledCanvas canvas]
  (raster/make-raster-layer (.getTileSize canvas)
                            :canvas canvas))

;; ═══════════════════════════════════════════════
;; 相机矩阵（列主序 viewProj）
;; ═══════════════════════════════════════════════

(defn- view-proj-mat4
  "列主序 4x4 viewProj。相机在 (0,0,eye-z) 看向 -Z。"
  ^floats [w h fov-y-deg
           near far eye-z]
  (let [f      (/ 1.0 (Math/tan (* 0.5 (* Math/PI (/ fov-y-deg 180.0)))))
        aspect (/ w h)
        nf     (- near far)
        a      (/ (+ far near) nf)
        b      (/ (* 2.0 far near) nf)]
    (float-array
      [(/ f aspect) 0  0  0
       0            f  0  0
       0            0  a  -1
       0            0  (+ (* a eye-z) b) (* -1.0 eye-z)])))

;; ═══════════════════════════════════════════════
;; Render → kernal
;; ═══════════════════════════════════════════════

(defn- render!
  "把 TiledCanvas 推给 kernal，触发一次重绘。"
  [kernal canvas ^long w ^long h]
  (let [viewport (vp/make-viewport)]
    (proto/set-layers!     kernal [(wrap-layer canvas)])
    (proto/set-image-size! kernal (int w) (int h))
    (proto/set-viewport!   kernal viewport)
    (proto/repaint         kernal [(viewport/refreshed)])))

;; ═══════════════════════════════════════════════
;; 会话
;; ═══════════════════════════════════════════════

(defn- start-session
  [^GLRuntime gl-runtime ^StackPane container _frame]
  (let [kernal   (.get (.getProperties container) kernal-fx-prop-key)
        {:keys [ctx programs]} (create-context gl-runtime)
        renderer (GLRenderer. (StudioPipeline.) ctx)
        cube     (mesh/make-cube-mesh)

        ;; 记录上一次已渲染的尺寸——宽、高各触发一次事件，去重
        last-size (atom nil)

        render-once!
        (fn [^long w ^long h]
          (println "render-one! called")
          (when-not (= @last-size [w h])
            (reset! last-size [w h])
            (let [request (-> (RenderRequest/builder)
                              (.viewMat4   (view-proj-mat4 (double w) (double h)
                                                           45.0 0.1 100.0 -3.0))
                              (.viewWidth  (int w))
                              (.viewHeight (int h))
                              (.objects    (Collections/singletonList cube))
                              (.build))]
              (-> (.render renderer request)
                  (.thenAccept
                    (reify Consumer
                      (accept [_ canvas]
                        (Platform/runLater
                          (reify Runnable
                            (run [_]
                              (println "studio render complete")
                              (render! kernal canvas w h)))))))))))

        on-size-change
        (reify InvalidationListener
          (invalidated [_ _]
            (let [w (long (.getWidth  container))
                  h (long (.getHeight container))]
              (when (and (pos? w) (pos? h))
                (render-once! w h)))))]

    ;; 立即尝试一次——如果 container 已经有尺寸
    (let [w (long (.getWidth  container))
          h (long (.getHeight container))]
      (when (and (pos? w) (pos? h))
        (render-once! w h)))

    ;; 监听尺寸变化——窗口缩放 / 布局变动时重渲
    (.addListener (.widthProperty  container) on-size-change)
    (.addListener (.heightProperty container) on-size-change)

    ;; cleanup
    (fn []
      (.removeListener (.widthProperty  container) on-size-change)
      (.removeListener (.heightProperty container) on-size-change)
      (try (.close renderer) (catch Throwable _))
      (try (.close ctx)      (catch Throwable _))
      (try (.close programs) (catch Throwable _))
      (try (.close cube)     (catch Throwable _)))))

;; ═══════════════════════════════════════════════
;; 组件工厂
;; ═══════════════════════════════════════════════

(defn make-factory
  "构造 modeling 画布组件工厂。
   kernal-supplier —— 决定渲染后端。签名 (fn [opts] -> {:container :kernal})。
   gl-runtime —— 3D 渲染使用的 GL runtime。"
  [kernal-supplier gl-runtime]
  (make-component
    []

    ;; create-fn：组件级
    (fn []
      (let [on-event (fn [_ev] (println "studio renderer on-event"))
            {:keys [^StackPane container kernal]}
            (kernal-supplier {:on-event on-event})]
        (.setMinWidth container 200)
        (.setMinHeight container 200)
        (.put (.getProperties container) kernal-fx-prop-key kernal)
        container))

    ;; init-fn：会话级
    (fn [^StackPane container _old-props _new-props frame]
      (start-session gl-runtime container frame))

    ;; dispose-fn：释放组件级
    :dispose-fn
    (fn [^StackPane container]
      (when-let [kernal (.get (.getProperties container) kernal-fx-prop-key)]
        (.remove (.getProperties container) kernal-fx-prop-key)
        (proto/close kernal)))))