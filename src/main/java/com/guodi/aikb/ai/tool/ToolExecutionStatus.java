package com.guodi.aikb.ai.tool;

/** Tool执行结果的后端状态协议，不交给LLM推断。 */
public final class ToolExecutionStatus {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private static final String FAILURE_PREFIX = "[AIKB_TOOL_ERROR] ";
    private static final String SPRING_TOOL_ERROR_PREFIX = "Error calling tool:";
    private static final String SPRING_GENERIC_ERROR_PREFIX = "Exception occurred in tool:";

    private ToolExecutionStatus() {
    }

    public static String failureResult(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return FAILURE_PREFIX + message;
    }

    public static boolean isFailure(String responseData) {
        if (responseData == null) {
            return false;
        }
        String normalized = responseData.stripLeading();
        return normalized.startsWith(FAILURE_PREFIX)
                || normalized.startsWith(SPRING_TOOL_ERROR_PREFIX)
                || normalized.startsWith(SPRING_GENERIC_ERROR_PREFIX);
    }

    public static String fromResult(String responseData) {
        return isFailure(responseData) ? FAILED : SUCCESS;
    }
}
