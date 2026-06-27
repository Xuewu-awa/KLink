package com.android1939.kardsserver.http;

import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class HttpRequest {
    public String method;
    public String path;
    public String rawPath;
    public String version = "HTTP/1.1";
    public String remoteAddress = "";
    public byte[] body;
    public final Map<String, String> headers = new HashMap<String, String>();
    public final Map<String, String> query = new HashMap<String, String>();

    public String header(String name) {
        return headers.get(name.toLowerCase());
    }

    public JSONObject jsonBody() throws Exception {
        if (body == null || body.length == 0) {
            return new JSONObject();
        }
        return new JSONObject(new String(body, "UTF-8"));
    }

    public Map<String, String> headersView() {
        return Collections.unmodifiableMap(headers);
    }
}
