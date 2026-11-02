(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.modeling-canvas
  (:require
    [top.kzre.krro.core.frame :as frame]
    [top.kzre.krro.core.reframe.core :as rf]
    [top.kzre.krro.plugin.modeling.core.project.scene :as scene]
    [top.kzre.krro.plugin.modeling.core.viewport.core :as core.viewport]
    [top.kzre.krro.plugin.modeling.editor.javafx.canvas.protocol :as protocol]
    [top.kzre.krro.ui.javafx.core :refer [make-component]])
  (:import
    (javafx.scene.layout StackPane)
    (top.kzre.krro.plugin.modeling.core.schedule RenderIds)))

(def ^:private state-atom-key ::state-atom)

(defn- start-session
  [^StackPane container frame
   {:keys [scene-id renderer-id]
    :or {renderer-id RenderIds/STUDIO}}]
  (let [state-atom (.get (.getProperties container) state-atom-key)
        {:keys [kernel]} @state-atom
        release-store (scene/reg-store scene-id)
        vp-watch-key (random-uuid)]
    (add-watch (frame/params-atom frame)
               vp-watch-key
               (fn [_ src old new]
                 (let [vp (core.viewport/get-viewport new)]
                   (protocol/set-viewport! kernel vp))))
    (swap! state-atom assoc :scene-id scene-id :frame frame)
    (protocol/set-scene! kernel scene-id)
    (protocol/set-renderer! kernel renderer-id)

    (fn []
      (remove-watch  (frame/params-atom frame) vp-watch-key)
      (release-store))))

;; ═══════════════════════════════════════════════
;; 组件工厂
;; ═══════════════════════════════════════════════

(defn make-factory
  "构造 modeling 画布组件工厂。
   kernel-supplier —— 决定渲染后端。签名 (fn [opts] -> {:container :kernel})。
   gl-runtime —— 3D 渲染使用的 GL runtime。"
  [kernel-supplier]
  (make-component
    [:scene-id
     :renderer-id]

    (fn []
      (let [state-atom (atom {})
            on-event (fn [ev]
                       (let [{:keys [scene-id frame kernel]} @state-atom
                             view-height (protocol/view-height kernel)]
                         (when scene-id
                           (rf/dispatch scene/app-id [:tool/dispatch-event scene-id ev frame
                                                      {:view-height view-height}]))))
            {:keys [^StackPane container kernel]}
            (kernel-supplier {:on-event on-event})]
        (swap! state-atom assoc :kernel kernel)
        (.put (.getProperties container) state-atom-key state-atom)
        container))

    (fn [^StackPane container _old-props new-props frame]
      (let [new-props (assoc new-props :scene-id 1)]
        (when (:scene-id new-props)
          (start-session container frame new-props))))

    :dispose-fn
    (fn [^StackPane container]
      (when-let [state-atom (.get (.getProperties container) state-atom-key)]
        (.remove (.getProperties container) state-atom-key)
        (let [kernel (:kernel @state-atom)]
          (protocol/close kernel))))))