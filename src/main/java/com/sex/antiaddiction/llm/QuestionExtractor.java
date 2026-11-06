package com.sex.antiaddiction.llm;

import com.quizgate.proto.QuestionDraft;
import java.util.List;

/**
 * 把一张卷子的图变成题目草稿。
 * <p>
 * 出题层一直是接口而不是具体某家模型：闸门侧只认 QuestionDraft，识别侧随时可以换成本地 OCR、
 * 别的云模型、或者测试里的假实现。native 冒烟必须走不打网络的那一路，所以这个口子不能焊死。
 */
public interface QuestionExtractor {

    /**
     * @param image 压缩后的图片字节，实现方不得落盘
     * @param mime  image/jpeg 或 image/png
     * @throws ExtractFailed 引擎没启用、没配口令、超时、返回的内容读不成题目
     */
    List<QuestionDraft> extract(byte[] image, String mime);
}
