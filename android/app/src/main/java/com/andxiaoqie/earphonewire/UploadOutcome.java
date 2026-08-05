package com.andxiaoqie.earphonewire;

public enum UploadOutcome {
    SUCCESS,
    REPAIR_REQUIRED,
    SUPERSEDED,
    RETRYABLE,
    REJECTED;

    public static UploadOutcome fromHttpStatus(int status) {
        if (status == 204) return SUCCESS;
        if (status == 401) return REPAIR_REQUIRED;
        if (status == 409) return SUPERSEDED;
        if (status == 408 || status == 429 || status >= 500) return RETRYABLE;
        return REJECTED;
    }
}
