package com.sex.antiaddiction.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 读写器。只用 JDK 语法，零反射、零依赖。
 * <p>
 * 为什么不直接用 classpath 上的 Jackson：Jackson 的 databind 在 native-image 下要靠反射注册元数据，
 * 而识别引擎这条路径在默认配置下不出网，冒烟脚本永远碰不到它——那正是"JVM 测试全绿、原生二进制一收请求就炸"
 * 的形状。这里写死的代码在两边是同一份，单测跑绿就等于原生可用。
 * <p>
 * 刻意只做"读出来"，不做 POJO 绑定：解析的是外部模型给的文本，取值一律给兜底默认，
 * 缺字段不能让解析器抛异常把整张卷子的识别结果丢掉。
 */
public final class MiniJson {

    /** 递归深度上限：模型回一个几万层嵌套的文档就该当它是坏数据，而不是把栈打爆。 */
    private static final int MAX_DEPTH = 64;

    private MiniJson() {
    }

    public sealed interface Node permits Obj, Arr, Str, Num, Bool, Null {

        default Node get(String field) {
            return this instanceof Obj object ? object.fields().getOrDefault(field, new Null()) : new Null();
        }

        default Node at(int index) {
            return this instanceof Arr array && index >= 0 && index < array.items().size()
                    ? array.items().get(index) : new Null();
        }

        default List<Node> items() {
            return this instanceof Arr array ? array.items() : List.of();
        }

        /** 文本值原样给；数字给它的原始写法；其余一律空串——调用方拿到空串就等于"没这个字段"。 */
        default String text() {
            if (this instanceof Str string) {
                return string.value();
            }
            return this instanceof Num number ? number.raw() : "";
        }

        default int intValue(int fallback) {
            try {
                return (int) Double.parseDouble(text());
            } catch (RuntimeException e) {
                return fallback;
            }
        }

        default boolean isNull() {
            return this instanceof Null;
        }
    }

    public record Obj(Map<String, Node> fields) implements Node {
    }

    public record Arr(List<Node> items) implements Node {
    }

    public record Str(String value) implements Node {
    }

    /** 数字保留原始写法：模型可能回 3 也可能回 3.0，转成 double 再转回来会把家长看到的文本改掉。 */
    public record Num(String raw) implements Node {
    }

    public record Bool(boolean value) implements Node {
    }

    public record Null() implements Node {
    }

    public static final class SyntaxException extends RuntimeException {
        public SyntaxException(String message) {
            super(message);
        }
    }

    /** 解析整个文档；尾部有多余内容、深度超限、语法不对都抛 SyntaxException。 */
    public static Node parse(String text) {
        var reader = new Reader(text == null ? "" : text);
        reader.skipSpace();
        var node = reader.value(0);
        reader.skipSpace();
        if (!reader.done()) {
            throw new SyntaxException("JSON 结尾后还有多余内容，位置 " + reader.position());
        }
        return node;
    }

    /**
     * 从一段可能混着解释文字、代码围栏的模型输出里把 JSON 抠出来。
     * <p>
     * 模型经常不守"只回 JSON"这句话：前面加一句"好的，以下是题目"，或者整段包在 ```json 里。
     * 这里取第一个出现的容器字符到它配平的那个字符为止；抠不出来就原样交给 parse()，让报错说真话。
     */
    public static String extractBlock(String text) {
        String source = text == null ? "" : text;
        int object = source.indexOf('{');
        int array = source.indexOf('[');
        int start = object < 0 ? array : (array < 0 ? object : Math.min(object, array));
        if (start < 0) {
            return source.trim();
        }
        char open = source.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.trim();
    }

    /** 把任意字符串写成合法的 JSON 字符串字面量（含引号）。非 ASCII 原样留着，请求体按 UTF-8 发出去。 */
    public static String quote(String value) {
        var sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Reader {

        private final String source;
        private int index;

        Reader(String source) {
            this.source = source;
        }

        boolean done() {
            return index >= source.length();
        }

        int position() {
            return index;
        }

        void skipSpace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        Node value(int depth) {
            if (depth > MAX_DEPTH) {
                throw new SyntaxException("JSON 嵌套超过 " + MAX_DEPTH + " 层，位置 " + index);
            }
            if (done()) {
                throw new SyntaxException("JSON 在值的位置就结束了");
            }
            char c = source.charAt(index);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new Str(string());
                case 't', 'f' -> literal();
                case 'n' -> nulls();
                default -> number();
            };
        }

        private Node object(int depth) {
            expect('{');
            var fields = new LinkedHashMap<String, Node>();
            skipSpace();
            if (peek() == '}') {
                index++;
                return new Obj(fields);
            }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                skipSpace();
                fields.put(key, value(depth + 1));
                skipSpace();
                char c = peek();
                index++;
                if (c == '}') {
                    return new Obj(fields);
                }
                if (c != ',') {
                    throw new SyntaxException("对象里该是逗号或右花括号，实得 " + c + "，位置 " + (index - 1));
                }
            }
        }

        private Node array(int depth) {
            expect('[');
            var items = new ArrayList<Node>();
            skipSpace();
            if (peek() == ']') {
                index++;
                return new Arr(items);
            }
            while (true) {
                skipSpace();
                items.add(value(depth + 1));
                skipSpace();
                char c = peek();
                index++;
                if (c == ']') {
                    return new Arr(items);
                }
                if (c != ',') {
                    throw new SyntaxException("数组里 expecting , 或 ] ，实得 " + c + "，位置 " + (index - 1));
                }
            }
        }

        private String string() {
            expect('"');
            var sb = new StringBuilder();
            while (!done()) {
                char c = source.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (done()) {
                    break;
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(unicode());
                    default -> throw new SyntaxException("不认识的转义 \\" + escaped + "，位置 " + (index - 1));
                }
            }
            throw new SyntaxException("字符串没有结束引号，位置 " + index);
        }

        private char unicode() {
            if (index + 4 > source.length()) {
                throw new SyntaxException("\\u 后面不足四位，位置 " + index);
            }
            String hex = source.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new SyntaxException("\\u 后面不是十六进制：" + hex);
            }
        }

        private Node literal() {
            if (source.startsWith("true", index)) {
                index += 4;
                return new Bool(true);
            }
            if (source.startsWith("false", index)) {
                index += 5;
                return new Bool(false);
            }
            throw new SyntaxException("不是 true/false，位置 " + index);
        }

        private Node nulls() {
            if (source.startsWith("null", index)) {
                index += 4;
                return new Null();
            }
            throw new SyntaxException("不是 null，位置 " + index);
        }

        private Node number() {
            int start = index;
            while (!done() && "-++.eE0123456789".indexOf(source.charAt(index)) >= 0) {
                index++;
            }
            if (start == index) {
                throw new SyntaxException("这个位置既不是值也不是空白：" + source.charAt(index) + "，位置 " + index);
            }
            String raw = source.substring(start, index);
            try {
                Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new SyntaxException("不是合法的数字：" + raw);
            }
            return new Num(raw);
        }

        private char peek() {
            if (done()) {
                throw new SyntaxException("JSON 提前结束");
            }
            return source.charAt(index);
        }

        private void expect(char c) {
            if (done() || source.charAt(index) != c) {
                throw new SyntaxException("这里应该是 " + c + "，位置 " + index);
            }
            index++;
        }
    }
}
