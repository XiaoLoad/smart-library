package com.library.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把上传目录里的图片转成 data URI。
 * <p>
 * 用途：微信小程序真机上 &lt;image&gt; 加载 http 资源会被客户端拦截，开发者工具的
 * 「不校验合法域名」对图片不生效；data URI 不经网络，可绕开该限制。
 * 代价是响应体膨胀，故默认按列表宽度缩放并压低 JPEG 质量，且带 LRU 缓存——
 * 同一张封面只在首次请求时编码。
 */
@Service
public class ImageBase64Service {

    /** 只放行上传目录下的资源，避免这个接口变成任意文件读取 */
    private static final String URI_PREFIX = "/api/uploads/";

    private static final Pattern SAFE_PATH = Pattern.compile("^/api/uploads/[A-Za-z0-9_\\-./]+\\.(jpg|jpeg|png|webp|gif)$");

    /** 单次请求的图片条数上限，配合前端一屏 12 条绰绰有余 */
    public static final int MAX_BATCH = 24;

    private static final int MIN_WIDTH = 32;
    private static final int MAX_WIDTH = 1080;

    private static final float JPEG_QUALITY = 0.72f;

    private static final int CACHE_CAPACITY = 512;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    private final Map<String, String> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    /**
     * @param uri   形如 /api/uploads/covers/1.jpg
     * @param width 目标宽度（等比缩放，不放大）；&lt;=0 表示按原图编码
     * @return data URI；路径非法或文件缺失/不可读时返回 null
     */
    public String toDataUri(String uri, int width) {
        if (uri == null || !SAFE_PATH.matcher(uri).matches()) {
            return null;
        }
        int w = width <= 0 ? 0 : Math.max(MIN_WIDTH, Math.min(width, MAX_WIDTH));
        String key = uri + "#" + w;
        String hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Path file = resolve(uri);
        if (file == null) {
            return null;
        }
        String dataUri = encode(file, w);
        if (dataUri != null) {
            cache.put(key, dataUri);
        }
        return dataUri;
    }

    /**
     * 把 URI 映射到磁盘文件，并确认结果仍在上传目录内。
     * {@code normalize()} 之后再做前缀比较，是防 {@code ../} 穿越的关键一步。
     */
    private Path resolve(String uri) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path file = root.resolve(uri.substring(URI_PREFIX.length())).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

    private String encode(Path file, int width) {
        try {
            BufferedImage src = ImageIO.read(file.toFile());
            // 读不出的格式（如 webp 未装解码器）退回原字节，至少不阻断前端
            if (src == null) {
                return rawFallback(file);
            }
            BufferedImage out = width > 0 && src.getWidth() > width ? scale(src, width) : src;
            byte[] jpeg = writeJpeg(out);
            if (jpeg == null) {
                return rawFallback(file);
            }
            return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
        } catch (IOException e) {
            return null;
        }
    }

    private BufferedImage scale(BufferedImage src, int width) {
        int height = Math.max(1, Math.round(src.getHeight() * (width / (float) src.getWidth())));
        // 带 alpha 的源图直接画到 TYPE_INT_RGB 画布，避免透明区在 JPEG 里变成黑块
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(src, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private byte[] writeJpeg(BufferedImage img) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } catch (IOException e) {
            return null;
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private String rawFallback(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        return "data:" + mimeOf(file.getFileName().toString()) + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    public void clearCache() {
        cache.clear();
    }
}
