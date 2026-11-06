package com.sex.antiaddiction.llm;

import com.quizgate.proto.CheckVerdict;
import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.Subject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 算术自校验的边界。这一层是"未核对题不给孩子做"唯一的自动担保，
 * 所以既测它该点头的地方，也测它该闭嘴的地方——放宽一档就等于把没验过的题直接推上孩子屏幕。
 */
class ArithmeticCheckTest {

    private static QuestionDraft draft(String stem, int correctIndex, String... options) {
        var builder = QuestionDraft.newBuilder()
                .setSubject(Subject.MATH)
                .setKnowledgePoint("测试知识点")
                .setStem(stem)
                .setCorrectIndex(correctIndex)
                .setExplanation("测试解析");
        for (var option : options) {
            builder.addOptions(option);
        }
        return builder.build();
    }

    @Test
    void 算得出来又和标注一致才算通过() {
        var ok = ArithmeticCheck.of(draft("3 + 5 = ？", 1, "7", "8", "9"));
        assertEquals(CheckVerdict.CHECK_CONFIRMED, ok.verdict());
        assertTrue(ok.note().contains("8"), "说明里要写下服务端算出来的那个数：" + ok.note());
        assertTrue(ok.confirmed());
    }

    /** 全角、汉字运算符、U+2212 减号都得认——真卷子上就是这么印的。 */
    @Test
    void 全角与汉字运算符一样能验算() {
        assertEquals(CheckVerdict.CHECK_CONFIRMED,
                ArithmeticCheck.of(draft("３ × ４ ＝", 0, "12", "8")).verdict());
        assertEquals(CheckVerdict.CHECK_CONFIRMED,
                ArithmeticCheck.of(draft("6乘7=", 1, "36", "42")).verdict());
        assertEquals(CheckVerdict.CHECK_CONFIRMED,
                ArithmeticCheck.of(draft("61 − 24 = ？", 0, "37", "43", "36")).verdict());
        assertEquals(CheckVerdict.CHECK_CONFIRMED,
                ArithmeticCheck.of(draft("12 ÷ 4 =", 0, "3", "8")).verdict());
    }

    /**
     * 答案标错时不能只说"不合格"，要说清算出来是几、标的是第几项——
     * 家长靠这句话判断到底是识别串行了还是自己点错了。
     */
    @Test
    void 答案标错要指出正确答案在哪一项() {
        var wrong = ArithmeticCheck.of(draft("3 + 5 = ？", 0, "7", "8", "9"));
        assertEquals(CheckVerdict.CHECK_MISMATCH, wrong.verdict());
        assertTrue(wrong.note().contains("8"), wrong.note());
        assertTrue(wrong.note().contains("第 2 项"), "要指到那个对的选项：" + wrong.note());
    }

    @Test
    void 选项里根本没有算出来的数时也算不合格() {
        var none = ArithmeticCheck.of(draft("3 + 5 = ？", 0, "7", "9", "11"));
        assertEquals(CheckVerdict.CHECK_MISMATCH, none.verdict());
    }

    /** 卷子上印的得数和服务端算的不一致：多半是识别串行，必须让人来看。 */
    @Test
    void 题干自带的得数和服务端算的不一致时报不符() {
        var clash = ArithmeticCheck.of(draft("3 + 5 = 7", 1, "7", "8"));
        assertEquals(CheckVerdict.CHECK_MISMATCH, clash.verdict());
        assertTrue(clash.note().contains("卷子上写的得数是 7"), clash.note());
    }

    @Test
    void 读不出算式就交回人工核对_不许蒙一个通过() {
        for (var stem : java.util.List.of(
                "「高兴」的近义词是哪个？",
                "小华有 5 个苹果，又买了 3 个，一共几个？",
                "twelve 是下面哪个数字？",
                "")) {
            var verdict = ArithmeticCheck.of(draft(stem, 0, "快乐", "难过"));
            assertEquals(CheckVerdict.CHECK_MANUAL, verdict.verdict(), "这道题不该被自动担保：" + stem);
            assertFalse(verdict.confirmed(), stem);
        }
    }

    /**
     * 一行里挤了两道口算（"3+5=8 4+2=6"）是识别串行的典型形状。
     * 这里如果继续往下算，就会拿第一道的得数去担保一整行——所以必须停手。
     */
    @Test
    void 两道口算粘成一行时不担保() {
        var merged = ArithmeticCheck.of(draft("3+5=8 4+2=6", 0, "8", "6"));
        assertEquals(CheckVerdict.CHECK_MANUAL, merged.verdict());
        assertTrue(merged.note().contains("等号"), merged.note());
    }

    /** 除不尽、小数、括号都不在自动验算范围内：宁可让家长多点一次核对。 */
    @Test
    void 算不出来的形状一律退回人工() {
        assertEquals(CheckVerdict.CHECK_MANUAL, ArithmeticCheck.of(draft("7 ÷ 2 =", 0, "3", "3.5")).verdict());
        assertEquals(CheckVerdict.CHECK_MANUAL,
                ArithmeticCheck.of(draft("(3 + 5) × 2 =", 0, "16", "13")).verdict());
        assertEquals(CheckVerdict.CHECK_MANUAL,
                ArithmeticCheck.of(draft("0.5 + 1.5 =", 0, "2", "3")).verdict());
    }

    /** 选项带单位（"8个"）时比不了：这种题服务端证明不了，也不许假装证明。 */
    @Test
    void 选项不是纯数字时退回人工() {
        var withUnit = ArithmeticCheck.of(draft("3 + 5 =", 0, "8个", "7个"));
        assertEquals(CheckVerdict.CHECK_MANUAL, withUnit.verdict());
        assertTrue(withUnit.note().contains("8"), "说明里要给出算出来的数，家长才有的对：" + withUnit.note());
    }

    /** 正确项下标越界（识别引擎给了个数组外的数）不能数组越界，要变成一条不合格。 */
    @Test
    void 正确项下标越界时报不合格而不是抛异常() {
        var outOfRange = ArithmeticCheck.of(draft("3 + 5 =", 9, "7", "8"));
        assertEquals(CheckVerdict.CHECK_MISMATCH, outOfRange.verdict());
    }
}
