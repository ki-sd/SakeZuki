package com.sakezuki.imagecore;

public final class ImageFailure extends Exception {
    public enum Category { IMAGE_ABSENT, INVALID_IMAGE, TRANSFORM_FAILED, STORAGE_FAILED }

    private final Category category;

    ImageFailure(Category category, String reason) {
        super(reason);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
