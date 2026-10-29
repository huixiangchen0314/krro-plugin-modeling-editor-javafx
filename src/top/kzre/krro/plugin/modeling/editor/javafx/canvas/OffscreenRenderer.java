package top.kzre.krro.plugin.modeling.editor.javafx.canvas;

import javafx.application.Platform;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 离屏 OpenGL 渲染器，在隐藏窗口中绘制一个线框立方体，并将结果输出到 JavaFX ImageView。
 * 所有 GL 操作在专用线程中执行，避免与 JavaFX 线程冲突。
 */
public class OffscreenRenderer {

    private long window;
    private int fbo, texture;
    private int width, height;
    private Consumer<WritableImage> onFrameReady;
    public OffscreenRenderer(){

    }


    // 单线程执行器，确保所有 GL 调用在同一线程和上下文
    private final ExecutorService glThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GL-Thread");
        t.setDaemon(true);
        return t;
    });

    /**
     * 初始化 OpenGL 上下文、FBO 等。必须在使用前调用一次。
     *
     * @param width  视口宽度
     * @param height 视口高度
     * @param onFrameReady 每+帧渲染完成后回调，在 JavaFX 线程中执行，传入生成的 WritableImage
     */
    public void init(int width, int height, Consumer<WritableImage> onFrameReady) throws Exception {
        this.width = width;
        this.height = height;
        this.onFrameReady = onFrameReady;

        // 阻塞等待 GL 初始化完成
        glThread.submit(() -> {
            try {
                if (!glfwInit()) {
                    throw new RuntimeException("Failed to initialize GLFW");
                }
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
                window = glfwCreateWindow(width, height, "Offscreen", MemoryUtil.NULL, MemoryUtil.NULL);
                if (window == 0L) {
                    throw new RuntimeException("Failed to create GLFW window");
                }
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                glEnable(GL_DEPTH_TEST);

                // 创建 FBO 和纹理
                fbo = glGenFramebuffers();
                texture = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                    throw new RuntimeException("Framebuffer is not complete");
                }
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }).get(); // 等待完成
    }

    /**
     * 由外部（如 JavaFX AnimationTimer）调用，触发一帧渲染。
     * 该方法立即返回，实际渲染在 GL 线程中异步执行，完成后回调更新 UI。
     */
    public void requestRender() {
        glThread.submit(() -> {
            try {
                if (window == 0L) return;
                glfwMakeContextCurrent(window);
                renderScene();
                WritableImage img = readPixels();
                Platform.runLater(() -> {
                    if (onFrameReady != null) {
                        onFrameReady.accept(img);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void renderScene() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);

        // 投影矩阵
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();
        double aspect = (double) width / (double) height;
        glFrustum(-aspect, aspect, -1.0, 1.0, 1.5, 20.0);
        glMatrixMode(GL_MODELVIEW);

        // 清屏
        glClearColor(0.2f, 0.2f, 0.2f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        // 绘制立方体
        glLoadIdentity();
        glTranslatef(0.0f, 0.0f, -5.0f);
        glColor3f(0.0f, 0.0f, 0.0f);

        float[][] vertices = {
                {-0.5f, -0.5f, -0.5f}, {0.5f, -0.5f, -0.5f}, {0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, -0.5f},
                {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {-0.5f, 0.5f, 0.5f}
        };
        int[][] edges = {
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7}
        };

        glBegin(GL_LINES);
        for (int[] edge : edges) {
            float[] v1 = vertices[edge[0]];
            float[] v2 = vertices[edge[1]];
            glVertex3f(v1[0], v1[1], v1[2]);
            glVertex3f(v2[0], v2[1], v2[2]);
        }
        glEnd();

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private WritableImage readPixels() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);

        WritableImage img = new WritableImage(width, height);
        int[] pixels = new int[width * height];

        // 翻转 Y 轴，并转换为 ARGB 格式（JavaFX 的 PixelFormat.getIntArgbInstance() 需要）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int srcIdx = ((height - 1 - y) * width + x) * 4;
                int r = buf.get(srcIdx) & 0xFF;
                int g = buf.get(srcIdx + 1) & 0xFF;
                int b = buf.get(srcIdx + 2) & 0xFF;
                int a = buf.get(srcIdx + 3) & 0xFF;
                pixels[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        img.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return img;
    }

    /**
     * 释放资源，必须在应用退出前调用。
     */
    public void destroy() {
        glThread.submit(() -> {
            if (window != 0L) {
                glfwDestroyWindow(window);
                window = 0L;
            }
            glfwTerminate();
        });
        glThread.shutdown();
    }
}