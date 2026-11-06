package com.sex.antiaddiction.llm;

/**
 * 识别失败。消息必须是能直接给家长看的人话：它会被原样放进 ScanBankReply.engine_message。
 * <p>
 * 所以这条异常里不许带口令、不许带整张应答原文，也不许带堆栈——那些进日志，不进接口应答。
 */
public class ExtractFailed extends RuntimeException {

    public ExtractFailed(String message) {
        super(message);
    }
}
