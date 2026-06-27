package com.android1939.kardsserver.http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpResponse {
    public int statusCode = 200;
    public String statusText = "OK";
    public String contentType = "text/plain; charset=utf-8";
    public byte[] body = new byte[0];
    public boolean closeConnection;
    public final Map<String, String> headers = new LinkedHashMap<String, String>();

    public static HttpResponse text(int code, String text) {
        HttpResponse response = new HttpResponse();
        response.statusCode = code;
        response.statusText = statusText(code);
        response.contentType = "text/plain; charset=utf-8";
        try {
            response.body = (text == null ? "" : text).getBytes("UTF-8");
        } catch (Exception ignored) {
        }
        return response;
    }

    public static HttpResponse json(int code, JSONObject json) {
        return jsonText(code, json == null ? "{}" : json.toString());
    }

    public static HttpResponse json(int code, JSONArray json) {
        return jsonText(code, json == null ? "[]" : json.toString());
    }

    public static HttpResponse jsonText(int code, String text) {
        HttpResponse response = text(code, text);
        response.contentType = "application/json";
        return response;
    }

    public static HttpResponse empty(int code) {
        return text(code, "");
    }

    public static String statusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 402: return "Payment Required";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            default: return "OK";
        }
    }
}
