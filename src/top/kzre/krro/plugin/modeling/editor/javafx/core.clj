(ns top.kzre.krro.plugin.modeling.editor.javafx.core
  (:require
   [top.kzre.krro.core.core :as krro]
   [top.kzre.krro.plugin.modeling.editor.core.core]
   [top.kzre.krro.plugin.modeling.editor.javafx.canvas.api :as canvas]))

(krro/reg-plugin!
  {:name :krro.modeling/editor-javafx
   :mount
   (fn []
     (krro/reg-plugin!
       {:id      :krro.modeling/canvas-tag
        :type    :krro.ui.javafx/tag
        :tag     :krro.modeling/canvas
        :factory canvas/create-gl-canvas})
     )})