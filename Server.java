import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Drop Count backend: plain JDK, no dependencies. Run: java Server.java */
public class Server {
  static final Map<String, Integer> rounds = new ConcurrentHashMap<>();
  static final Map<String, Integer> totals = new ConcurrentHashMap<>();
  static final Random rnd = new Random();

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
    s.setExecutor(Executors.newFixedThreadPool(8));

    s.createContext("/", ex -> {
      if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
      send(ex, 200, "text/html; charset=utf-8", new String(Files.readAllBytes(Path.of("index.html")), StandardCharsets.UTF_8));
    });

    // New round: returns a drop schedule [startMs, fallMs, xPercent, colorIdx]. The count is never sent as a field.
    s.createContext("/api/round", ex -> {
      int level = Math.max(1, Math.min(3, parse(q(ex).get("level"), 1)));
      int[] lo = {5, 10, 20}, span = {10, 16, 26};
      int n = lo[level - 1] + rnd.nextInt(span[level - 1]);
      if (rounds.size() > 5000) rounds.clear();
      String id = UUID.randomUUID().toString();
      rounds.put(id, n);
      StringBuilder sb = new StringBuilder("{\"id\":\"" + id + "\",\"drops\":[");
      int t = 0;
      for (int i = 0; i < n; i++) {
        t += 60 + rnd.nextInt(level == 3 ? 250 : 650);           // irregular gaps, sometimes bursts
        int fall = 500 + rnd.nextInt(2200 / level);               // varying speeds
        if (i > 0) sb.append(',');
        sb.append('[').append(t).append(',').append(fall).append(',').append(rnd.nextInt(100)).append(',').append(rnd.nextInt(6)).append(']');
      }
      send(ex, 200, "application/json", sb.append("]}").toString());
    });

    // Score a guess: up to 100 pts by closeness, +50 for an exact count.
    s.createContext("/api/guess", ex -> {
      Map<String, String> p = q(ex);
      Integer actual = rounds.remove(p.getOrDefault("round", ""));
      if (actual == null) { send(ex, 400, "application/json", "{\"error\":\"unknown or already used round\"}"); return; }
      int guess = parse(p.get("guess"), -1);
      if (guess < 0) { send(ex, 400, "application/json", "{\"error\":\"bad guess\"}"); return; }
      int pts = Math.max(0, (int) Math.round(100.0 * (1.0 - Math.abs(guess - actual) / (double) actual)));
      if (guess == actual) pts += 50;
      String name = p.getOrDefault("name", "").replaceAll("[^A-Za-z0-9 _-]", "").trim();
      if (name.isEmpty()) name = "anon";
      if (name.length() > 16) name = name.substring(0, 16);
      int total = totals.merge(name, pts, Integer::sum);
      send(ex, 200, "application/json", "{\"actual\":" + actual + ",\"points\":" + pts + ",\"total\":" + total + "}");
    });

    s.createContext("/api/leaderboard", ex -> {
      StringBuilder sb = new StringBuilder("[");
      totals.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(10).forEach(e -> {
        if (sb.length() > 1) sb.append(',');
        sb.append("{\"name\":\"").append(e.getKey()).append("\",\"total\":").append(e.getValue()).append('}');
      });
      send(ex, 200, "application/json", sb.append(']').toString());
    });

    s.start();
    System.out.println("Drop Count running on http://localhost:" + port);
  }

  static int parse(String v, int d) { try { return Integer.parseInt(v); } catch (Exception e) { return d; } }

  static Map<String, String> q(HttpExchange ex) {
    Map<String, String> m = new HashMap<>();
    String s = ex.getRequestURI().getRawQuery();
    if (s == null) return m;
    for (String p : s.split("&")) {
      int i = p.indexOf('=');
      if (i > 0) m.put(p.substring(0, i), URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
    }
    return m;
  }

  static void send(HttpExchange ex, int code, String type, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", type);
    ex.getResponseHeaders().set("Cache-Control", "no-store");
    ex.sendResponseHeaders(code, b.length);
    try (OutputStream o = ex.getResponseBody()) { o.write(b); }
  }
}
