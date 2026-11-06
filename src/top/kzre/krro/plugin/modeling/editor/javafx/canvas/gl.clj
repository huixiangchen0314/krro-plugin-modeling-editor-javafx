(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.gl
  (:require
   [top.kzre.krro.core.util.promise :as promise :refer [plet]]
   [top.kzre.krro.plugin.painting.core.project.canvas :as pc])
  (:import
    (top.kzre.krro.canvas.gl.resource PixelFormat)
    (top.kzre.krro.d3.renderer.gl GLRenderFlyweight GLStudioRenderContext)
    (top.kzre.krro.plugin.painting.editor.javafx.glfx GLRuntime)))

(defn render-flyweight-promise
  "渲染享元上下文"
  [^GLRuntime gl-runtime]
  (let [tile-size pc/global-tile-size
        executor  (.getExecutor gl-runtime)]
    (promise/from-completable-future
      (.computeIfAbsent
        gl-runtime
        GLRenderFlyweight
        (reify
          Callable
          (call [_]
            (-> (GLRenderFlyweight/builder)
                (.tileSize tile-size)
                (.glExecutor executor)
                (.build))))
        ;; 立即执行
        false))))

(defn studio-render-context-promise
  [gl-runtime]
  (plet [flyweight (render-flyweight-promise gl-runtime)]
        (GLStudioRenderContext. flyweight PixelFormat/RGBA8)))