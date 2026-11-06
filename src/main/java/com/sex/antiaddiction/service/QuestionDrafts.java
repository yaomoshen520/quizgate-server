package com.sex.antiaddiction.service;

import com.quizgate.proto.QuestionDraft;
import com.quizgate.proto.Subject;
import com.sex.antiaddiction.domain.Rows;

/**
 * 题目草稿的字段校验和入库映射。
 * <p>
 * 手打上传和拍照识别必须过同一套校验：识别那条路一旦放宽规则，家长就会以为"相机拍的题也是审过的"，
 * 而实际落库的是没人查过的空选项或越界下标。规则只写在这一处。
 */
final class QuestionDrafts {

    private QuestionDrafts() {
    }

    /** 返回 null 表示合格；否则是给家长看的那一条原因。 */
    static String validate(int index, QuestionDraft draft) {
        String at = "第 " + (index + 1) + " 题：";
        if (draft.getStem().isBlank()) {
            return at + "题干为空";
        }
        if (draft.getOptionsCount() < 2) {
            return at + "至少两个选项";
        }
        if (draft.getCorrectIndex() < 0 || draft.getCorrectIndex() >= draft.getOptionsCount()) {
            return at + "正确项下标越界";
        }
        if (draft.getSubject() == Subject.SUBJECT_UNSPECIFIED) {
            return at + "学科未指定";
        }
        if (draft.getGrade() < 0 || draft.getKnowledgePoint().isBlank()) {
            return at + "年级或知识点缺失";
        }
        return null;
    }

    /** grade 留空就跟着孩子的年级走，家长不必为每道题重填。 */
    static Rows.Question toRow(QuestionDraft draft, int childGrade, boolean verified) {
        int grade = draft.getGrade() > 0 ? draft.getGrade() : childGrade;
        return new Rows.Question(0L, draft.getSubject(), grade, draft.getKnowledgePoint(), draft.getStem(),
                draft.getOptionsList(), draft.getCorrectIndex(), draft.getExplanation(), verified);
    }
}
