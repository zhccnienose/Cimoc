package com.haleydu.cimoc.source;

import com.haleydu.cimoc.model.Chapter;
import com.haleydu.cimoc.model.Comic;
import com.haleydu.cimoc.model.ImageUrl;
import com.haleydu.cimoc.model.Source;
import com.haleydu.cimoc.parser.MangaParser;
import com.haleydu.cimoc.parser.NodeIterator;
import com.haleydu.cimoc.parser.SearchIterator;
import com.haleydu.cimoc.parser.UrlFilter;
import com.haleydu.cimoc.soup.Node;
import com.haleydu.cimoc.utils.DecryptionUtils;
import com.haleydu.cimoc.utils.StringUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import okhttp3.Headers;
import okhttp3.Request;

/**
 * Created by Hiroshi on 2016/7/8.
 */
public class IKanman extends MangaParser {

    // TODO 实测联通4G网络无法使用看漫画

    public static final int TYPE = 0;
    public static final String DEFAULT_TITLE = "漫画柜";

    private String referer = "";

    public IKanman(Source source) {
        init(source, null);
//        init(source, new Category());
    }

    public static Source getDefaultSource() {
        return new Source(null, DEFAULT_TITLE, TYPE, false);
    }

    @Override
    public Request getSearchRequest(String keyword, int page) {
        String url = StringUtils.format("https://www.manhuagui.com/s/%s_p%d.html", keyword, page);
        return new Request.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 4.1.1; Nexus 7 Build/JRO03D) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166  Safari/535.19")
                .url(url)
                .build();
    }

    @Override
    public SearchIterator getSearchIterator(String html, int page) {
        Node body = new Node(html);
        return new NodeIterator(body.list("li.cf")) {
            @Override
            protected Comic parse(Node node) {
                String cid = node.hrefWithSplit("a.bcover", 1);
                String title = node.text(".book-detail > dl > dt > a");
                String cover = node.attr("a.bcover > img", "src");
//                String update = node.text("dl:eq(5) > dd");
//                String author = node.text("dl:eq(2) > dd");
                return new Comic(TYPE, cid, title, cover, "", "");
            }
        };
    }

    @Override
    public String getUrl(String cid) {
        return "https://tw.manhuagui.com/comic/".concat(cid);
    }

    @Override
    protected void initUrlFilterList() {
        filter.add(new UrlFilter("www.manhuagui.com"));
        filter.add(new UrlFilter("tw.manhuagui.com"));
        filter.add(new UrlFilter("m.manhuagui.com"));
    }

    @Override
    public Request getInfoRequest(String cid) {
        String url = "https://tw.manhuagui.com/comic/".concat(cid).concat("/");
        return new Request.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 4.1.1; Nexus 7 Build/JRO03D) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166  Safari/535.19")
                .url(url)
                .build();
    }

    @Override
    public Comic parseInfo(String html, Comic comic) {
        Node body = new Node(html);
        String title = body.text("div.book-title > h1");
        String cover = body.src("p.hcover > img");
        String update = body.text("div.chapter-bar > span.fr > span:eq(1)");
        String author = body.attr("ul.detail-list > li:eq(1) > span:eq(1) > a", "title");
        String intro = body.text("#intro-cut");
        boolean status = isFinish(body.text("div.chapter-bar > span.fr > span:eq(0)"));
        comic.setInfo(title, cover, update, intro, author, status);
        return comic;
    }

    @Override
    public List<Chapter> parseChapter(String html, Comic comic, Long sourceComic) {
        List<Chapter> list = new LinkedList<>();
        Node body = new Node(html);
        String baseText = body.id("__VIEWSTATE").attr("value");
        if (!StringUtils.isEmpty(baseText)) {
            body = new Node(DecryptionUtils.LZ64Decrypt(baseText));
        }
        int i=0;
        for (Node node : body.list("div.chapter-list")) {
            List<Node> uls = node.list("ul");
            Collections.reverse(uls);
            for (Node ul : uls) {
                for (Node li : ul.list("li > a")) {
                    String title = li.attr("title");
                    String path = li.hrefWithSplit(2);
                    list.add(new Chapter(Long.parseLong(sourceComic + "000" + i++), sourceComic, title, path));
                }
            }
        }
        return list;
    }

    @Override
    public Request getImagesRequest(String cid, String path) {
        String url = StringUtils.format("https://tw.manhuagui.com/comic/%s/%s.html", cid, path);
        referer = url;
        return new Request.Builder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 4.1.1; Nexus 7 Build/JRO03D) AppleWebKit/535.19 (KHTML, like Gecko) Chrome/18.0.1025.166  Safari/535.19")
//            .addHeader("Referer", StringUtils.format("https://m.manhuagui.com/comic/%s/%s.html", cid, path))
                .url(url)
                .build();
    }

    @Override
    public List<ImageUrl> parseImages(String html, Chapter chapter) {
        List<ImageUrl> list = new LinkedList<>();
        String packed = StringUtils.match("\\(function\\(p,a,c,k,e,d\\).*?0,\\{\\}\\)\\)", html, 0);
        if (packed != null) {
            try {
                String replaceable = StringUtils.split(packed, ",", -3);
                String fake = StringUtils.split(replaceable, "'", 1);
                String real = DecryptionUtils.LZ64Decrypt(fake);
                packed = packed.replace(replaceable, StringUtils.format("'%s'.split('|')", real));
                String result = DecryptionUtils.evalDecrypt(packed);

                String jsonString = result.substring(12, result.length() - 12);
                JSONObject object = new JSONObject(jsonString);
                String chapterId = object.getString("cid");
                String path = object.getString("path");
//                String md5 = object.getJSONObject("sl").getString("md5");
                String e = object.getJSONObject("sl").getString("e");
                String m = object.getJSONObject("sl").getString("m");
                JSONArray array = object.getJSONArray("files");
                for (int i = 0; i != array.length(); ++i) {
                    Long comicChapter = chapter.getId();
                    Long id = Long.parseLong(comicChapter + "000" + i);
                    String url = StringUtils.format("https://i.hamreus.com%s%s?e=%s&m=%s", path, array.getString(i), e, m);
                    list.add(new ImageUrl(id, comicChapter, i + 1, url, false));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return list;
    }

    @Override
    public Request getCheckRequest(String cid) {
        return getInfoRequest(cid);
    }

    @Override
    public String parseCheck(String html) {
        return new Node(html).text("div.chapter-bar > span.fr > span:eq(1)");
    }

    @Override
    public List<Comic> parseCategory(String html, int page) {
        List<Comic> list = new ArrayList<>();
        Node body = new Node(html);
        for (Node node : body.list("#AspNetPager1 > span.current")) {
            try {
                if (Integer.parseInt(node.text()) < page) {
                    return list;
                }
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        for (Node node : body.list("#contList > li")) {
            String cid = node.hrefWithSplit("a", 1);
            String title = node.attr("a", "title");
            String cover = node.src("a > img");
            if (StringUtils.isEmpty(cover)) {
                cover = node.attr("a > img", "data-src");
            }
            String update = node.textWithSubstring("span.updateon", 4, 14);
            list.add(new Comic(TYPE, cid, title, cover, update, null));
        }
        return list;
    }

    @Override
    public Headers getHeader() {
//        return Headers.of("Referer", "https://tw.manhuagui.com/comic/30449/408812.html",
        return Headers.of("Referer", referer,
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/67.0.3396.99 Safari/537.36");
    }

}
