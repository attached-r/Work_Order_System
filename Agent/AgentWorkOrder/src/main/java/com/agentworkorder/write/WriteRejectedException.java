package com.agentworkorder.write;

/**
 * agent 侧拒绝一次写操作（令牌失效、参数不符、每轮超限等）。
 * <p>
 * 工具层捕获后转成 {@code {code,message}} 错误体交给模型转述，而不是把异常抛进
 * Spring AI 的工具调用链。{@code code} 与项目一错误体口径一致（400/409）。
 */
public class WriteRejectedException extends RuntimeException {

    private final int code;

    public WriteRejectedException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
