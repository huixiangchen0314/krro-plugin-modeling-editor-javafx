(ns top.kzre.krro.plugin.modeling.editor.javafx.canvas.mesh
  "固定立方体 mesh 的构造。"
  (:import
    (java.util Collections)
    (top.kzre.krro.d3.core.util.cow CopyOnWriteObject
                                    DefaultCopyOnWriteFloats
                                    DefaultCopyOnWriteObject
                                    ListResource)
    (top.kzre.krro.d3.renderer.studio.pipeline RenderableMesh)
    (top.kzre.krro.util.arena Arenas ArenaTemplate FloatArenaView FloatArrayArena)
    (top.kzre.krro.util.math KMath)))

(def ^:private cube-positions
  "36 顶点 × 3 float。中心原点，边长 1。"
  (float-array
    [-0.5 -0.5 -0.5   0.5 -0.5 -0.5   0.5  0.5 -0.5
     -0.5 -0.5 -0.5   0.5  0.5 -0.5  -0.5  0.5 -0.5
     -0.5 -0.5  0.5   0.5  0.5  0.5   0.5 -0.5  0.5
     -0.5 -0.5  0.5  -0.5  0.5  0.5   0.5  0.5  0.5
     -0.5 -0.5 -0.5  -0.5  0.5 -0.5  -0.5  0.5  0.5
     -0.5 -0.5 -0.5  -0.5  0.5  0.5  -0.5 -0.5  0.5
     0.5 -0.5 -0.5   0.5 -0.5  0.5   0.5  0.5  0.5
     0.5 -0.5 -0.5   0.5  0.5  0.5   0.5  0.5 -0.5
     -0.5  0.5 -0.5   0.5  0.5 -0.5   0.5  0.5  0.5
     -0.5  0.5 -0.5   0.5  0.5  0.5  -0.5  0.5  0.5
     -0.5 -0.5 -0.5  -0.5 -0.5  0.5   0.5 -0.5  0.5
     -0.5 -0.5 -0.5   0.5 -0.5  0.5   0.5 -0.5 -0.5]))

(defn- alloc-float-view
  "从 arena 分配容纳 n 个 float 的 view。byteSize = n * 4。"
  ^FloatArenaView [^FloatArrayArena arena ^long n]
  (let [byteSize (int (* n 4))
        result   (.allocate (ArenaTemplate. arena) byteSize)]
    (if (.isSuccess result)
      (.getView result)
      (throw (IllegalStateException.
               (str "arena allocation failed: " n " floats ("
                    byteSize " bytes)"))))))

(defn- fill-view!
  "把 float[] 拷贝到 view 的存储区（view.offset 是 float 索引）。"
  [^FloatArenaView view ^floats src]
  (let [dst   (.getFloats view)
        off   (int (.offset view))
        src-n (alength src)
        cnt   (int (.count view))]
    (when (> src-n cnt)
      (throw (IllegalArgumentException.
               (str "src has " src-n " floats, view only " cnt))))
    (System/arraycopy src 0 dst off src-n)))

(defn make-cube-mesh
  "构造立方体 RenderableMesh。返回的 mesh 持有 COW 链，调用方负责 close。"
  ^RenderableMesh []
  (let [n      (alength cube-positions)
        arena  (Arenas/newSingleFloatArray n)
        view   (alloc-float-view arena n)
        _      (fill-view! view cube-positions)
        floats (DefaultCopyOnWriteFloats. view)
        list   (ListResource. (Collections/singletonList floats))
        cow    (DefaultCopyOnWriteObject. list)]
    (RenderableMesh. ^CopyOnWriteObject cow
                     ^floats (KMath/mat4Identity))))