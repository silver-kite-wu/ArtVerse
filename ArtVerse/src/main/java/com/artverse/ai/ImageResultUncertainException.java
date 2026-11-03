package com.artverse.ai;

/** A provider response arrived, but its image could not be recovered or saved safely. */
public class ImageResultUncertainException extends RuntimeException {
    public ImageResultUncertainException(Throwable cause) { super("图片请求可能已完成，请核对外部结果",cause); }
}
