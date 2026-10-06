import kotlinx.serialization.json.*; import java.util.*; import java.nio.charset.StandardCharsets;
public class Esc { public static void main(String[] a) throws Exception {
  String s = "p\"ss\\w/rd-🔑\u0001\t\n\u007f é";
  String q = JsonElementKt.JsonPrimitive(s).toString();
  System.out.println(q);
  StringBuilder hex = new StringBuilder(); for (byte b : q.getBytes(StandardCharsets.UTF_8)) hex.append(String.format(Locale.ROOT, "%02x", b)); System.out.println(hex);
  LinkedHashMap<String, JsonElement> m = new LinkedHashMap<>(); m.put("schema", JsonElementKt.JsonPrimitive(1)); m.put("n", JsonElementKt.JsonPrimitive(1791244800000L)); m.put("e", JsonElementKt.JsonPrimitive((String) null));
  System.out.println(new JsonObject(m).toString());
  JsonElement parsed = Json.Default.parseToJsonElement("{\"n\":1.50,\"list\":[true,null,\"x\"], \"u\":\"\\u00e9\\/\"}");
  System.out.println(parsed.toString());
}}
