package com.sex.antiaddiction.llm;

import com.quizgate.proto.CheckVerdict;
import com.quizgate.proto.QuestionDraft;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务端自己重算一遍算术题，作为"这道题的答案有人担保过"的第一道证据。
 * <p>
 * 担保的对象是**入库后家长和孩子实际看到的那份文本**：题干的算式是服务端读的，答案下标是标注的，
 * 两边都对得上才算 CONFIRMED。识别模型把题干读歪时，读到的算式和选项就自相矛盾，落点是 MISMATCH，
 * 不是"蒙对一个是一个"。反过来，题干里读不出算式（应用题、拼音、英语）就一律 MANUAL，
 * 证明不了的东西不在这里装能证明。
 */
public final class ArithmeticCheck {

    private ArithmeticCheck() {
    }

    public record Verdict(CheckVerdict verdict, String note) {

        public boolean confirmed() {
            return verdict == CheckVerdict.CHECK_CONFIRMED;
        }
    }

    public static Verdict of(QuestionDraft draft) {
        String stem = normalize(draft.getStem());
        var runs = expressionRuns(stem);
        if (runs.isEmpty()) {
            return manual("题干里没有能自动验算的算式，答案要你人工核对。");
        }
        if (runs.size() > 1) {
            return manual("题干里有不止一道算式，服务端不猜你说的是哪一道。");
        }
        String run = runs.get(0);
        if (countChar(run, '=') > 1) {
            return manual("题干里有不止一个等号，多半是把两道题读成了一行。");
        }

        int equals = run.indexOf('=');
        String leftSide = equals < 0 ? run : run.substring(0, equals);
        String rightSide = equals < 0 ? "" : run.substring(equals + 1).trim();
        Long computed = evaluate(leftSide);
        if (computed == null) {
            return manual("算式「" + leftSide + "」服务端算不出来（小数、括号或除不尽不在自动验算范围内）。");
        }
        String expression = leftSide;
        if (!rightSide.isEmpty()) {
            // 答案位读得出来就对它，读不干净就直接放弃担保——"3+5=8 4+2" 这种粘连多半是识别串行。
            Long onPaper = asInteger(rightSide);
            if (onPaper == null) {
                return manual("等号后面那一段「" + rightSide + "」读不成一个数，先人工看一眼。");
            }
            if (onPaper.longValue() != computed) {
                return new Verdict(CheckVerdict.CHECK_MISMATCH,
                        "卷子上写的得数是 " + onPaper + "，服务端重算「" + expression + "」得 " + computed + "。");
            }
        }

        var options = draft.getOptionsList();
        int marked = draft.getCorrectIndex();
        if (marked < 0 || marked >= options.size()) {
            return new Verdict(CheckVerdict.CHECK_MISMATCH,
                    "标注的正确项下标 " + marked + " 不在选项范围内，服务端算出来是 " + computed + "。");
        }
        Long markedValue = asInteger(options.get(marked));
        if (markedValue == null) {
            return manual("服务端算出来是 " + computed + "，但第 " + (marked + 1) + " 项「" + options.get(marked)
                    + "」不是个纯数字，比不了。");
        }
        if (markedValue.equals(computed)) {
            return new Verdict(CheckVerdict.CHECK_CONFIRMED,
                    "服务端重算「" + expression + "」得 " + computed + "，和你标的正确项一致。");
        }
        int matchIndex = -1;
        for (int i = 0; i < options.size(); i++) {
            if (computed.equals(asInteger(options.get(i)))) {
                matchIndex = i;
                break;
            }
        }
        if (matchIndex >= 0) {
            return new Verdict(CheckVerdict.CHECK_MISMATCH,
                    "服务端算出来是 " + computed + "，那是第 " + (matchIndex + 1) + " 项；你标的是第 "
                            + (marked + 1) + " 项「" + options.get(marked) + "」。");
        }
        return new Verdict(CheckVerdict.CHECK_MISMATCH,
                "服务端算出来是 " + computed + "，可这些选项里没有这个数。");
    }

    private static Verdict manual(String note) {
        return new Verdict(CheckVerdict.CHECK_MANUAL, note);
    }

    /**
     * 全角、汉字运算符统一成 ASCII。
     * <p>
     * "除以"必须在"除"之前替换（"6除2"和"6除以2"是反的两回事，这里干脆不认单字"除"），
     * "乘以"同理要在"乘"之前。分数（"3分之2"）不认，读不成整数算式就交给 MANUAL。
     */
    static String normalize(String stem) {
        String text = stem == null ? "" : stem;
        text = text.replace("除以", "/")
                .replace("乘以", "*").replace("加", "+").replace("减", "-")
                .replace("乘", "*").replace("÷", "/").replace("／", "/")
                .replace("×", "*").replace("✕", "*").replace("﹡", "*").replace("＊", "*")
                .replace("＋", "+").replace("﹢", "+").replace("－", "-").replace("−", "-")
                .replace("–", "-").replace("＝", "=");
        var sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 抠出"数字与运算符连在一起"的片段。空格算片段内部的分隔（"3 + 5"要认），
     * 换行算片段边界（两行口算不能粘成一行）。
     */
    static List<String> expressionRuns(String text) {
        var runs = new ArrayList<String>();
        var current = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            char c = i == text.length() ? '\n' : text.charAt(i);
            if (c == '\n' || c == '\r') {
                offer(runs, current);
                current.setLength(0);
            } else if (c == ' ' || c == '\t' || c == '　') {
                current.append(' ');
            } else if ("0123456789+-*/=.".indexOf(c) >= 0) {
                current.append(c);
            } else {
                offer(runs, current);
                current.setLength(0);
            }
        }
        return runs;
    }

    private static void offer(List<String> runs, StringBuilder current) {
        String candidate = current.toString().replace(" ", "");
        if (candidate.isEmpty()) {
            return;
        }
        // 光秃秃一个数字（"小明有 5 个"）不是算式；得真带个运算符。
        if (candidate.matches(".*[+*/\\-].*") && candidate.chars().anyMatch(Character::isDigit)) {
            runs.add(candidate);
        }
    }

    private static int countChar(String text, char c) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                count++;
            }
        }
        return count;
    }

    /** 纯整数（可带正负号），带单位或带汉字的都不认。 */
    private static Long asInteger(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.length() > 1 && (value.charAt(0) == '+' || value.charAt(0) == '-')) {
            value = value.substring(1);
        }
        if (value.isEmpty() || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 递归下降：expr = term (('+'|'-') term)*，term = number (('*'|'/') number)*。
     * 不认括号、不认一元负号、不认小数、除不尽直接判算不出来——宁可少担保一道，不错担保一道。
     *
     * @return 算不出来时给 null，由调用方转成 MANUAL
     */
    static Long evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        var parser = new Parser(expression.trim());
        try {
            Long value = parser.expression();
            return parser.done() ? value : null;
        } catch (ArithmeticException | IllegalStateException e) {
            return null;
        }
    }

    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean done() {
            return index >= text.length();
        }

        Long expression() {
            long value = term();
            while (!done()) {
                char c = text.charAt(index);
                if (c != '+' && c != '-') {
                    break;
                }
                index++;
                long right = term();
                value = c == '+' ? Math.addExact(value, right) : Math.subtractExact(value, right);
            }
            return value;
        }

        private long term() {
            long value = number();
            while (!done()) {
                char c = text.charAt(index);
                if (c != '*' && c != '/') {
                    break;
                }
                index++;
                long right = number();
                if (c == '*') {
                    value = Math.multiplyExact(value, right);
                } else {
                    // 除不尽就放弃担保：小学题的答案是整数，出现小数说明读歪了或者本来就是分数题。
                    if (right == 0L || value % right != 0) {
                        throw new ArithmeticException("除不尽");
                    }
                    value = value / right;
                }
            }
            return value;
        }

        private long number() {
            int start = index;
            while (!done() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw new IllegalStateException("这里该是一个数，位置 " + index);
            }
            return Long.parseLong(text.substring(start, index));
        }
    }
}
