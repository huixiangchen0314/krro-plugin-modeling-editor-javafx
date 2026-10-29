(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.opengl
  (:import (java.util.function Consumer)
           (javafx.scene.image ImageView)
           (top.kzre.krro.plugin.modeling.editor.javafx.canvas OffscreenRenderer)))


(defonce renderer (atom nil))

(defn init-offscreen-renderer [^ImageView iv]
  (let [r (OffscreenRenderer.)]
    (.init r 800 600
           (reify Consumer
             (accept [_ img]
               (.setImage iv img))))
    (reset! renderer r)))

(defn render-and-update []
  (when-let [r @renderer]
    (.requestRender r)))

(defn destroy []
  (when-let [r @renderer]
    (.destroy r)
    (reset! renderer nil)))