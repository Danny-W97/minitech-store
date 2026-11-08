package org.demo.customerservice.export.jobs;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class ExportJson {
    private final ObjectMapper mapper;
    public ExportJson(){
        var time=new SimpleModule();time.addSerializer(Instant.class,new JsonSerializer<Instant>(){
            @Override public void serialize(Instant value,JsonGenerator output,SerializerProvider provider)throws IOException{output.writeString(value.toString());}
        });
        mapper=new ObjectMapper().registerModule(time).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public JsonNode tree(Object value){return mapper.valueToTree(value);}
    public JsonNode parse(byte[] value){try{return mapper.readTree(value);}catch(IOException error){throw new ExportFailure("SOURCE_FORMAT");}}
    public String write(Object value){try{return mapper.writeValueAsString(value);}catch(IOException error){throw new ExportFailure("SOURCE_FORMAT");}}
    public int bytes(String value){return value.getBytes(StandardCharsets.UTF_8).length;}
}
