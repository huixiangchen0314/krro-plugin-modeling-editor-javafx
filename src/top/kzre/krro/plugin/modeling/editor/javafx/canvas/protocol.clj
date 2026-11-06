(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.protocol)

(defprotocol ICanvasKernel
  (set-renderer! [_ render-id] "设置渲染器")
  (set-scene! [_ scene-id] "设置显示的场景")
  (set-viewport! [_ viewport] "设置视口变换")
  (overlay-gc [_] "获取叠加层图形上下文协议，一般是 extend-type 原生画布")
  (view-aspect [_] "获取视口 宽/高 比")
  (view-height [_] "获取视口高度")
  (view-width [_] "获取视口宽度")
  (close [_] "释放"))