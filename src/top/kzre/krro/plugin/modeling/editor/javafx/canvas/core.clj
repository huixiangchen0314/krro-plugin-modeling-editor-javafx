(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.core
  (:require
    [top.kzre.krro.plugin.modeling.editor.javafx.canvas.opengl :as opengl]
    [top.kzre.krro.ui.javafx.core :refer [make-component]])
  (:import
    (javafx.animation AnimationTimer)
    (javafx.scene Node)
    (javafx.scene.canvas Canvas)
    (javafx.scene.image ImageView)
    (javafx.scene.layout StackPane)))

(def ^:private canvas-width  800)
(def ^:private canvas-height 600)

(defn- create-view []
  (let [canvas     (Canvas. canvas-width canvas-height)
        image-view (ImageView.)
        root       (StackPane.)]
    ;; 让子节点填满 StackPane
    (.setManaged image-view false)
    (.setManaged canvas false)
    (doseq [^Node n [image-view canvas]]
      (.add (.getChildren root) n))
    ;; 把 ImageView 引用挂到根节点上，start-session 里取用
    (.setUserData root {:canvas canvas :image-view image-view})
    root))

(defn- start-session [^StackPane root _frame]
  (let [{:keys [^ImageView image-view]} (.getUserData root)]
    ;; 1) 初始化离屏渲染器（内部会阻塞直到 GL 上下文就绪）
    (opengl/init-offscreen-renderer image-view)

    ;; 2) 每帧驱动一次渲染
    (let [timer (proxy [AnimationTimer] []
                  (handle [_now]
                    (opengl/render-and-update)))]
      (.start timer)

      ;; 3) 清理函数：先停 timer 再销毁 GL
      (fn dispose-fn []
        (.stop timer)
        (opengl/destroy)))))

(def create-canvas
  (make-component
    []
    ;; create-fn
    create-view
    ;; init-fn（注意类型提示已改为 StackPane）
    (fn [^StackPane root _old-props _new-props frame]
      (start-session root frame))))