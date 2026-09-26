package com.sHDFGamePlugin.infrastructure.item;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 玩法说明书（成书）：内容来自插件数据目录下的 {@link #RESOURCE_NAME}（纯文本、可由服主直接编辑）。
 *
 * <h2>文件格式</h2>
 * <pre>
 * title: 玩法说明          ← 可选，成书标题（默认「玩法说明」）
 * author: 服务器           ← 可选，成书作者（默认「服务器」）
 * ---                      ← 页分隔符（独占一行），每段成为书的一页
 * 第一页正文...
 * ---
 * 第二页正文...
 * </pre>
 * 正文支持 {@code &} 颜色/格式码（如 {@code &6&l}、{@code &f}），由 Adventure 的 legacy 序列化解析；
 * 单页超过 {@link #MAX_PAGE_CHARS} 字符时会自动分页，避免超出客户端一页的容量。
 *
 * <h2>缓存与重载</h2>
 * 内容首次取用时读取并缓存；{@link #reload()} 可丢弃缓存（便于调试或后续接入 /reload）。
 * 文件缺失时会先从 jar 内释放默认内容（{@code saveResource}），因此升级插件后首次启动即可用。
 */
public final class GuideBookFactory {

    /** 数据目录下的说明文件（jar 内同名资源为默认内容） */
    public static final String RESOURCE_NAME = "gameplay_guide.txt";

    /** 未配置时的默认标题/作者 */
    private static final String FALLBACK_TITLE = "玩法说明";
    private static final String FALLBACK_AUTHOR = "服务器";

    /** 单页最多视觉行数（客户端一页约 14 行，这里留 1 行余量） */
    private static final int MAX_PAGE_LINES = 13;
    /**
     * 单行可用宽度（单位＝半角字符宽）。
     * <p>客户端书页每行约能容纳这么多"半角宽度"：中文等全角字符按 2 计、ASCII 按 1 计，
     * 这样"行会不会折行"才能被准确估算——只按字符数分页会导致页面显示不全。</p>
     */
    private static final int LINE_WIDTH_UNITS = 24;
    /** 单页字符数兜底上限（防止极端情况单页过重） */
    private static final int MAX_PAGE_CHARS = 500;

    /** 页分隔符（独占一行，两侧空白忽略） */
    private static final String PAGE_SEPARATOR = "---";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;

    public GuideBookFactory(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** 缓存：成书标题/作者与正文各页（null 表示尚未加载） */
    private Component title;
    private Component author;
    private List<Component> pages;

    /**
     * 生成一本说明书（每名玩家拿到的是独立副本，可安全放进玩家背包）。
     * <p>内容来自 {@link #RESOURCE_NAME}；文件不可读或为空时回退为提示页，绝不抛异常打断准备阶段。</p>
     */
    public ItemStack createBook() {
        ensureLoaded();

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) {
            return book;
        }
        meta.title(title);
        meta.author(author);
        meta.addPages(pages.toArray(new Component[0]));
        //快捷栏/背包里显示的名字（成书标题只在打开后显示，这里另给一个好认的物品名）
        meta.displayName(Component.text("玩法说明", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("右键翻开阅读", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)));
        book.setItemMeta(meta);
        return book;
    }

    /** 丢弃缓存：下次取书时重新读取文件内容 */
    public void reload() {
        title = null;
        author = null;
        pages = null;
    }

    // ==================== 加载 ====================

    private void ensureLoaded() {
        if (pages != null) {
            return;
        }
        String raw = readRawText();
        apply(raw);
    }

    /** 读取数据目录下的说明文件；缺失时先释放 jar 内默认内容。失败返回 null（由调用方回退） */
    private String readRawText() {
        try {
            File file = new File(plugin.getDataFolder(), RESOURCE_NAME);
            if (!file.exists()) {
                plugin.saveResource(RESOURCE_NAME, false);
            }
            if (!file.exists()) {
                return null;
            }
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("[GuideBook] 无法读取 " + RESOURCE_NAME + "，将使用内置提示页: " + e.getMessage());
            return null;
        }
    }

    /** 解析文本：可选 title/author 头、{@code ---} 分页、超长自动分页、{@code &} 颜色码 */
    private void apply(String raw) {
        String bookTitle = FALLBACK_TITLE;
        String bookAuthor = FALLBACK_AUTHOR;
        List<String> rawPages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        if (raw != null) {
            //-1 保留末尾空段，保证文件末尾换行不影响分页结果
            for (String line : raw.split("\\R", -1)) {
                String trimmed = line.trim();
                if (PAGE_SEPARATOR.equals(trimmed)) {
                    rawPages.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                //标题/作者只允许出现在第一页正文之前
                if (rawPages.isEmpty() && current.length() == 0) {
                    String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
                    if (lower.startsWith("title:")) {
                        String value = trimmed.substring("title:".length()).trim();
                        if (!value.isEmpty()) {
                            bookTitle = value;
                        }
                        continue;
                    }
                    if (lower.startsWith("author:")) {
                        String value = trimmed.substring("author:".length()).trim();
                        if (!value.isEmpty()) {
                            bookAuthor = value;
                        }
                        continue;
                    }
                }
                current.append(line).append('\n');
            }
            rawPages.add(current.toString());
        }

        List<Component> built = new ArrayList<>();
        for (String pageText : rawPages) {
            //跳过空页：文件头的 title/author 与第一个 --- 之间会形成一个空段，若不跳过会显示成空白第一页
            if (isVisuallyBlank(pageText)) {
                continue;
            }
            for (String chunk : paginate(pageText)) {
                if (!isVisuallyBlank(chunk)) {
                    built.add(deserialize(chunk));
                }
            }
        }
        if (built.isEmpty()) {
            //成书页面是浅色背景：默认文字用黑色，提示用深灰；浅色（&f/&e/&7）会看不清
            built.add(deserialize("&4&l说明文件为空。&r\n&8请检查插件数据目录下的 " + RESOURCE_NAME + "。"));
        }

        //成书标题/作者为纯文本（客户端不渲染其中的格式码）
        this.title = Component.text(bookTitle);
        this.author = Component.text(bookAuthor);
        this.pages = built;
    }

    /**
     * 按"视觉行数"切页：单页不超过 {@link #MAX_PAGE_LINES} 行，行宽按 {@link #LINE_WIDTH_UNITS}
     * 估算（全角按 2、半角按 1、颜色码不计宽）。
     *
     * <p><b>整行不可跨页</b>：一条源文本行若会折成多行，则整条一起留在本页或整体移到下一页，
     * 否则会出现"下一页只剩半句话"的断句（例如只剩"开启"两个字）。</p>
     */
    private static List<String> paginate(String pageText) {
        List<String> result = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int usedLines = 0;
        for (String line : pageText.split("\\R", -1)) {
            int lineCount = Math.max(1, (int) Math.ceil(visualWidth(line) / (double) LINE_WIDTH_UNITS));
            if (usedLines > 0
                    && (usedLines + lineCount > MAX_PAGE_LINES || page.length() + line.length() > MAX_PAGE_CHARS)) {
                result.add(page.toString());
                page.setLength(0);
                usedLines = 0;
            }
            page.append(line).append('\n');
            usedLines += lineCount;
        }
        if (page.length() > 0) {
            result.add(page.toString());
        }
        return result;
    }

    /** 该行宽度（单位＝半角字符宽；颜色码不计宽） */
    private static int visualWidth(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            if (isCodeStart(line, i)) {
                i++;
                continue;
            }
            width += charWidth(line.charAt(i));
        }
        return width;
    }

    /** 是否为 {@code &x} 颜色/格式码的起始位置 */
    private static boolean isCodeStart(String text, int index) {
        if (text.charAt(index) != '&' || index + 1 >= text.length()) {
            return false;
        }
        char next = Character.toLowerCase(text.charAt(index + 1));
        return (next >= '0' && next <= '9') || (next >= 'a' && next <= 'f') || next == 'k' || next == 'l'
                || next == 'm' || next == 'n' || next == 'o' || next == 'r';
    }

    /** 半角字符计 1、全角（中文等）计 2：近似客户端字形宽度 */
    private static int charWidth(char c) {
        return c < 0x2E80 ? 1 : 2;
    }

    /** 去掉颜色码后是否没有可见内容（用于跳过空页） */
    private static boolean isVisuallyBlank(String text) {
        StringBuilder visible = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (isCodeStart(text, i)) {
                i++;
                continue;
            }
            visible.append(text.charAt(i));
        }
        return visible.toString().trim().isEmpty();
    }

    /** 解析 {@code &} 颜色码；解析失败时退化为纯文本（说明书内容不得打断准备阶段） */
    private static Component deserialize(String text) {
        try {
            return LEGACY.deserialize(text)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
        } catch (RuntimeException e) {
            return Component.text(text);
        }
    }
}
