package com.haleydu.cimoc.model;

/**
 * 漫画章节。
 *
 * <p>迁移说明：已移除 greenDAO 注解（{@code @Entity}/{@code @Id}/{@code @Generated}…）
 * 与 {@code Parcelable} 实现。前者是 Android ORM 的编译期标记，后者用于 Android 的
 * 跨进程传递——桌面端两者都没有意义，这里回归成纯 POJO。
 *
 * <p>原先依赖 Parcelable 传递章节的地方（如 Intent extras）在桌面端应改为直接传对象引用。
 */
public class Chapter {

    private Long id;
    private Long sourceComic;
    private String title;
    private String path;
    private int count;
    private boolean complete;
    private boolean download;
    private long tid;
    private String sourceGroup;

    public Chapter(Long id, Long sourceComic, String title, String path, long tid) {
        this(id, sourceComic, title, path, 0, false, false, tid, "");
    }

    public Chapter(Long id, Long sourceComic, String title, String path, String sourceGroup) {
        this(id, sourceComic, title, path, 0, false, false, -1, sourceGroup);

    }

    public Chapter(Long id, Long sourceComic, String title, String path) {
        this(id, sourceComic, title, path, 0, false, false, -1, "");
    }

    public Chapter(String title, String path) {
        this.title = title;
        this.path = path;
        this.count = 0;
        this.complete = false;
        this.download = false;
        this.tid = -1;
    }

    public Chapter(Long id, Long sourceComic, String title, String path, int progress, boolean b, boolean b1, Long id1) {
        this(id, sourceComic, title, path, progress, b, b1, id1, "");

    }

    public Chapter(Long id, Long sourceComic, String title, String path, int count, boolean complete, boolean download, long tid, String sourceGroup) {
        this.id = id;
        this.sourceComic = sourceComic;
        this.title = title;
        this.path = path;
        this.count = count;
        this.complete = complete;
        this.download = download;
        this.tid = tid;
        this.sourceGroup = sourceGroup;
    }

    public String getSourceGroup() {
        return sourceGroup == null ? "" : sourceGroup;
    }

    public void setSourceGroup(String sourceGroup) {
        this.sourceGroup = sourceGroup;
    }

    public Chapter() {
    }

    public String getTitle() {
        return title;
    }

    public String getPath() {
        return path;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public boolean isDownload() {
        return download;
    }

    public void setDownload(boolean download) {
        this.download = download;
    }

    public long getTid() {
        return tid;
    }

    public void setTid(long tid) {
        this.tid = tid;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSourceComic() {
        return sourceComic;
    }

    public void setSourceComic(Long sourceComic) {
        this.sourceComic = sourceComic;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chapter && ((Chapter) o).path.equals(path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean getComplete() {
        return this.complete;
    }

    public boolean getDownload() {
        return this.download;
    }

}
