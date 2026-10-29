(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.api
  (:require
    [top.kzre.krro.plugin.modeling.editor.javafx.canvas.modeling-canvas :as modeling-canvas]
    [top.kzre.krro.plugin.painting.editor.javafx.canvas.gl-canvas-kernal :as gl-kernal])
  (:import (top.kzre.krro.plugin.painting.editor.javafx.glfx GLRuntime)))


(def create-gl-canvas
  (modeling-canvas/make-factory
    (fn [opts]
      (gl-kernal/build-kernal (GLRuntime/offscreen) opts))
    (GLRuntime/offscreen)))