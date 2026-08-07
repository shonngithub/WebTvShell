package com.webtvshell.app.config;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {
    private final List<String> errors;

    ValidationResult(List<String> errors) {
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return errors;
    }

    public JSONObject toJson() {
        JSONObject result = new JSONObject();
        try {
            result.put("valid", isValid());
            result.put("errors", new JSONArray(errors));
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
        return result;
    }
}
