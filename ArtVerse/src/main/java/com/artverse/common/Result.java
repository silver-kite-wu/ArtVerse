package com.artverse.common;

import lombok.Data;

@Data
public class Result<T> {

    /** 业务状态码：0 成功，非 0 失败 */
    public static final int CODE_SUCCESS = 0;

    private int code;

    /** 提示信息 */
    private String message;

    /** 返回数据，失败时为 null */
    private T data;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success() {
        return new Result<>(CODE_SUCCESS, "操作成功", null);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(CODE_SUCCESS, message, data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }
}
