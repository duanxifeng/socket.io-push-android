package com.yy.httpproxy.util;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by xuduo on 6/20/16.
 */
public class JSONUtil {

    public static Map<String, String> toMapOneLevelString(JSONObject object) {
        Map<String, String> map = new HashMap<>();
        try {
            Iterator<String> keysItr = object.keys();
            while (keysItr.hasNext()) {
                String key = keysItr.next();
                map.put(key, object.get(key).toString());
            }
        } catch (JSONException e) {
        }

        return map;
    }

    public static JSONObject toJSONObject(Map<String, String> map) {
        JSONObject object = new JSONObject();
        try {
            if (map != null) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    object.put(entry.getKey(), entry.getValue());
                }
            }
        } catch (JSONException e) {
        }
        return object;
    }

    public static String[] toStringArray(JSONArray jsonArray) {
        if (jsonArray != null) {
            String[] array = new String[jsonArray.length()];
            for (int i = 0; i < jsonArray.length(); i++) {
                array[i] = jsonArray.optString(i, "");
            }
            return array;
        } else {
            return new String[]{};
        }
    }

    public static Set<String> toStringSet(JSONArray jsonArray) {
        if (jsonArray != null) {
            Set<String> array = new HashSet<>(jsonArray.length());
            for (int i = 0; i < jsonArray.length(); i++) {
                array.add(jsonArray.optString(i, ""));
            }
            return array;
        } else {
            return new HashSet<>();
        }
    }

    public static JSONArray toJSONArray(Collection list) {
        JSONArray array = new JSONArray();
        if (list != null) {
            for (Object o : list) {
                array.put(o);
            }
        }
        return array;
    }
}
