package com.artverse.application.imagequeue;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

final class ImageJson {
    private static final ObjectMapper MAPPER=JsonMapper.builder().findAndAddModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
    static String write(Object value) { try { return MAPPER.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid image data",e); } }
    static <T> T read(String value,Class<T> type) { try { return MAPPER.readValue(value,type); } catch(Exception e) { throw new IllegalArgumentException("Invalid image data",e); } }
}
