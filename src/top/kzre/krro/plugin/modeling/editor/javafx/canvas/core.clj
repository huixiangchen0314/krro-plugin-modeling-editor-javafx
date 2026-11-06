(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.core
  (:require
   [top.kzre.krro.core.util.promise :as promise]
   [top.kzre.krro.plugin.modeling.core.project.accel :as accel]
   [top.kzre.krro.plugin.modeling.core.project.asset :as asset]
   [top.kzre.krro.plugin.modeling.core.project.core :as project]
   [top.kzre.krro.plugin.modeling.editor.javafx.canvas.gl :as gl]
   [top.kzre.krro.plugin.modeling.editor.javafx.canvas.gl-canvas-kernel :as gl-kernal]
   [top.kzre.krro.plugin.modeling.editor.javafx.canvas.modeling-canvas :as modeling-canvas])
  (:import
    (top.kzre.krro.d3.core.scene AssetMeshRef)
    (top.kzre.krro.plugin.modeling.core.project DefaultK3DRuntime K3DRuntime ProjectScene)
    (top.kzre.krro.plugin.modeling.core.schedule.gl GLSceneSchedulerHolderFactory)
    (top.kzre.krro.plugin.painting.editor.javafx.glfx GLRuntime)
    (top.kzre.krro.util.arena Arenas AutoGrowFloatArrayArenaTemplate)))

;; 初始化 运行时
(let [flyweight (promise/await (gl/render-flyweight-promise (GLRuntime/offscreen)))
      scheduler-holder-factory (GLSceneSchedulerHolderFactory. flyweight)
      farena (Arenas/newEmptyFloatArray)
      f-tpl (AutoGrowFloatArrayArenaTemplate/fixedSize farena (* 1024 1024))
      k3d-impl (-> (DefaultK3DRuntime/builder)
                   (.schedulerFactory scheduler-holder-factory)
                   (.meshAccelFactory (accel/mesh-accel-factory))
                   (.floatArenaTemplate f-tpl)
                   (.build))]
  (K3DRuntime/init k3d-impl))
;; mock 数据
(def scene-id 1)
(project/create-scene! scene-id)
(project/write-asset-lib!
  (fn [asset-lib]
    (let [cube-asset-id (asset/add-cube asset-lib)]
      (project/write-scene!
        1
        (fn [scene]
          (.addRoot ^ProjectScene scene
                    (AssetMeshRef. scene-id cube-asset-id)))))))


(def create-gl-canvas
  (modeling-canvas/make-factory
    (fn [opts]
      (gl-kernal/build-kernel (GLRuntime/offscreen) opts))))