package com.sex.antiaddiction.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手写 JSON 读器的代价必须由测试付掉：它解析的是外部模型给的文本，读歪一处就会把整张卷子的结果丢掉，
 * 或者更糟——把该拒的脏数据当合法应答收下。
 */
class MiniJsonTest {

    @Test
    void 对象数组嵌套都读得回来() {
        var node = MiniJson.parse("""
                {"output":{"choices":[{"message":{"content":[{"text":"你好"}]}}]},"ok":true,"n":null}""");
        assertEquals("你好", node.get("output").get("choices").at(0).get("message").get("content").at(0)
                .get("text").text());
        assertEquals(1, node.get("output").get("choices").items().size());
        assertTrue(MiniJson.parse("{}").get("missing").isNull(), "缺字段要读成空，不是抛异常");
        assertTrue(MiniJson.parse("[]").at(3).isNull(), "下标越界要读成空，不是抛异常");
        assertTrue(MiniJson.parse("3").get("any").isNull(), "对非对象取值不该抛，交给调用方兜底");
    }

    @Test
    void 转义与unicode读得对() {
        // 这段 Java 字面量落到运行期就是 {"s":"a\"b\\c\nd\u4e2d文"}：JSON 里该有的反斜杠一个都不能少。
        var json = "{\"s\":\"a\\\"b\\\\c\\nd\\u4e2d文\"}";
        assertEquals("a\"b\\c\nd中文", MiniJson.parse(json).get("s").text());
    }

    /** 数字保留原始写法：模型回 3 还是 3.0，家长看到的题干和答案不该被我们改形状。 */
    @Test
    void 数字按原文保留并能取整() {
        assertEquals("3.0", MiniJson.parse("{\"n\":3.0}").get("n").text());
        assertEquals("12", MiniJson.parse("{\"n\":12}").get("n").text());
        assertEquals(12, MiniJson.parse("{\"n\":12}").get("n").intValue(0));
        assertEquals(-1, MiniJson.parse("{\"n\":\"不是数\"}").get("n").intValue(-1), "读不出整数就用兜底值");
        assertEquals(0, MiniJson.parse("{}").get("n").intValue(0));
    }

    @Test
    void 坏语法一律报错而不是悄悄返回半个结构() {
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{\"a\":1,}"), "尾逗号必须报错");
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{\"a\":1"));
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{} junk"));
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{\"a\":}"));
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("\"没关门"));
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{\"a\":0x10}"));
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse("{\"a\":\"\\q\"}"));
    }

    /** 模型回一个几万层嵌套的文档时，栈不该被打爆。 */
    @Test
    void 嵌套过深当坏数据处理() {
        String deep = "[".repeat(200) + "]".repeat(200);
        assertThrows(MiniJson.SyntaxException.class, () -> MiniJson.parse(deep));
    }

    @Test
    void 从混着解释和代码围栏的输出里抠出JSON() {
        var fenced = MiniJson.parse(MiniJson.extractBlock("```json\n[{\"stem\":\"3+5=\"}]\n```"));
        assertEquals("3+5=", fenced.at(0).get("stem").text());

        var chatty = MiniJson.parse(MiniJson.extractBlock("好的，以下是题目：\n[{\"stem\":\"甲\"},{\"stem\":\"乙\"}]"));
        assertEquals(2, chatty.items().size());

        // 字符串里带 } 时不能把块提前收口——这是抠块唯一的难点，钉住它。
        var tricky = MiniJson.extractBlock("这是结果 {\"s\":\"a}b\"} 以上");
        assertEquals("{\"s\":\"a}b\"}", tricky);
    }

    @Test
    void 写请求体时把危险字符全关掉() {
        assertEquals("\"a\\\"b\"", MiniJson.quote("a\"b"));
        assertEquals("\"a\\\\b\"", MiniJson.quote("a\\b"));
        assertEquals("\"a\\nb\"", MiniJson.quote("a\nb"));
        assertEquals("\"a\\u0001b\"", MiniJson.quote("a\u0001b"));
        // 中文原样留着：请求体按 UTF-8 发，转成 unicode 转义也行，但那样日志里的图数据 URL 更难读。
        assertEquals("\"题干\"", MiniJson.quote("题干"));
        // 拼进去的字符串必须还是合法 JSON——这一条是"家长传的文本不能改写请求形状"的证明。
        var roundTrip = (MiniJson.Obj) MiniJson.parse("{\"k\":" + MiniJson.quote("evil\"},\"x\":\"y") + "}");
        assertEquals("evil\"},\"x\":\"y", roundTrip.get("k").text());
        assertEquals(1, roundTrip.fields().size());
    }
}
