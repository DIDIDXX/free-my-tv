package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data and Asset manager for the full-screen "Flamengo" tab dashboard:
 * - Standings Window (8 teams around Flamengo)
 * - Last 5 match results with GE TV YouTube highlight video IDs
 * - Next 5 fixtures with tournament and team IDs
 * - Cached bitmaps for crests and tournament trophies
 */
final class FlamengoData {
    public static final class Fixture {
        public final String homeTeam;
        public final String awayTeam;
        public final String homeId;
        public final String awayId;
        public final String dateTime; // "22/10 21:30"
        public final String tournament; // "Libertadores 2026" or "Brasileirão 2026"

        public Fixture(String homeTeam, String awayTeam, String homeId, String awayId, String dateTime, String tournament) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.homeId = homeId;
            this.awayId = awayId;
            this.dateTime = dateTime;
            this.tournament = tournament;
        }

        public String datePart() {
            if (dateTime == null) return "";
            int sp = dateTime.indexOf(' ');
            return sp > 0 ? dateTime.substring(0, sp) : dateTime;
        }

        public String timePart() {
            if (dateTime == null) return "";
            int sp = dateTime.indexOf(' ');
            return sp > 0 ? dateTime.substring(sp + 1) : "";
        }
    }

    public static final class MatchResult {
        public final String result; // "V", "E", "D"
        public final String oppName;
        public final String oppId;
        public final boolean isHome; // true = Flamengo home, false = Flamengo away
        public final String scoreText; // "0x3", "1x0", etc.
        public final String tournament; // "Brasileirão 2026"
        public final String videoId; // YouTube video ID for GE TV highlights

        public MatchResult(String result, String oppName, String oppId, boolean isHome, String scoreText, String tournament, String videoId) {
            this.result = result;
            this.oppName = oppName;
            this.oppId = oppId;
            this.isHome = isHome;
            this.scoreText = scoreText;
            this.tournament = (tournament != null && !tournament.isEmpty()) ? tournament : "Brasileirão 2026";
            this.videoId = videoId != null ? videoId : "";
        }

        public String homeTeam() {
            return isHome ? "Flamengo" : oppName;
        }

        public String awayTeam() {
            return isHome ? oppName : "Flamengo";
        }

        public String homeId() {
            return isHome ? "819" : oppId;
        }

        public String awayId() {
            return isHome ? oppId : "819";
        }

        public String formattedScore() {
            if (scoreText == null) return "0 - 0";
            return scoreText.replace("x", " - ").replace("X", " - ");
        }
    }

    public static final class StandingRow {
        public final String rank;
        public final String teamId;
        public final String name;
        public final String points;
        public final String played;
        public final String wins;
        public final String goalDiff;
        public final boolean isFla;

        public StandingRow(String rank, String teamId, String name, String points, String played, String wins, String goalDiff, boolean isFla) {
            this.rank = rank.replace("º", "");
            this.teamId = teamId;
            this.name = name;
            this.points = points;
            this.played = played;
            this.wins = wins;
            this.goalDiff = goalDiff;
            this.isFla = isFla;
        }
    }

    private static FlamengoData instance;
    private static final String PREFS = "flamengo_dashboard_v2";
    private static final long REFRESH_INTERVAL = 20 * 60_000L;
    private static final Pattern YT_VIDEO_ID_PATTERN = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"");

    static synchronized FlamengoData get(Context c) {
        if (instance == null) instance = new FlamengoData(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> crestCache = new HashMap<>();
    private Bitmap flamengoCrest, brasileiraoLogo, libertadoresLogo;
    private long lastFetch;
    private boolean fetching;

    public final List<Fixture> nextFixtures = new ArrayList<>();
    public final List<MatchResult> lastResults = new ArrayList<>();
    public final List<StandingRow> standings = new ArrayList<>();

    private FlamengoData(Context c) {
        this.context = c.getApplicationContext();
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        loadAssets();
        loadCached();
    }

    private void loadAssets() {
        flamengoCrest = loadPngAsset("flamengo/flamengo_shield.png");
        brasileiraoLogo = loadPngAsset("flamengo/brasileirao.png");
        libertadoresLogo = loadPngAsset("flamengo/libertadores.png");
    }

    private Bitmap loadPngAsset(String path) {
        try (InputStream in = context.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    public Bitmap getFlamengoCrest() {
        return flamengoCrest;
    }

    public Bitmap getBrasileiraoLogo() {
        return brasileiraoLogo;
    }

    public Bitmap getLibertadoresLogo() {
        return libertadoresLogo;
    }

    public synchronized Bitmap getTeamCrest(String teamId) {
        if (teamId == null || teamId.isEmpty()) return null;
        if ("819".equals(teamId)) return flamengoCrest;
        if (crestCache.containsKey(teamId)) return crestCache.get(teamId);
        Bitmap b = loadPngAsset("flamengo/teams/" + teamId + ".png");
        if (b != null) crestCache.put(teamId, b);
        return b;
    }

    private void loadCached() {
        String json = prefs.getString("payload", null);
        lastFetch = prefs.getLong("last_fetch", 0);
        if (json != null) {
            parsePayload(json);
        }
        if (nextFixtures.isEmpty() || standings.size() < 8 || lastResults.isEmpty()) {
            setupMockDefaults();
        }
    }

    private void setupMockDefaults() {
        nextFixtures.clear();
        nextFixtures.add(new Fixture("Flamengo", "Estudiantes", "819", "estudiantes", "22/10 21:30", "Libertadores 2026"));
        nextFixtures.add(new Fixture("Flamengo", "Atlético-MG", "819", "7632", "26/10 20:00", "Brasileirão 2026"));
        nextFixtures.add(new Fixture("Vasco", "Flamengo", "3454", "819", "29/10 20:00", "Brasileirão 2026"));
        nextFixtures.add(new Fixture("Grêmio", "Flamengo", "6273", "819", "04/11 19:30", "Brasileirão 2026"));
        nextFixtures.add(new Fixture("Flamengo", "Cruzeiro", "819", "2022", "08/11 21:00", "Brasileirão 2026"));

        lastResults.clear();
        lastResults.add(new MatchResult("V", "Bahia", "9967", false, "0x3", "Brasileirão 2026", "4-LPaBTA068"));
        lastResults.add(new MatchResult("V", "Fluminense", "3445", true, "1x0", "Brasileirão 2026", "NZCLBDNb-o0"));
        lastResults.add(new MatchResult("E", "Santos", "2674", false, "2x2", "Brasileirão 2026", "_Y5B3JOtdYI"));
        lastResults.add(new MatchResult("V", "Bragantino", "6079", true, "2x1", "Brasileirão 2026", "Qw19l7_Tm1Q"));
        lastResults.add(new MatchResult("V", "Corinthians", "874", true, "2x1", "Brasileirão 2026", "oxXjXSwQ40w"));

        standings.clear();
        standings.add(new StandingRow("1", "819", "Flamengo", "67", "31", "20", "36", true));
        standings.add(new StandingRow("2", "2029", "Palmeiras", "66", "31", "19", "32", false));
        standings.add(new StandingRow("3", "3458", "Athletico-PR", "53", "31", "15", "12", false));
        standings.add(new StandingRow("4", "2022", "Cruzeiro", "51", "31", "15", "4", false));
        standings.add(new StandingRow("5", "3445", "Fluminense", "51", "31", "14", "10", false));
        standings.add(new StandingRow("6", "9967", "Bahia", "49", "31", "13", "8", false));
        standings.add(new StandingRow("7", "6086", "Botafogo", "48", "31", "13", "6", false));
        standings.add(new StandingRow("8", "2026", "São Paulo", "47", "31", "13", "5", false));
    }

    public void refresh(final Runnable onDone) {
        long now = System.currentTimeMillis();
        if (fetching || (now - lastFetch < REFRESH_INTERVAL && standings.size() >= 8)) {
            if (onDone != null) onDone.run();
            return;
        }
        fetching = true;
        io.execute(() -> {
            try {
                JSONObject payload = new JSONObject();

                // 1. Standings (8 teams around Flamengo)
                String sRaw = fetchHttp("https://site.api.espn.com/apis/v2/sports/soccer/bra.1/standings");
                if (sRaw != null) {
                    JSONObject sObj = new JSONObject(sRaw);
                    JSONArray children = sObj.optJSONArray("children");
                    if (children != null && children.length() > 0) {
                        JSONArray entries = children.getJSONObject(0).getJSONObject("standings").getJSONArray("entries");
                        int flaIdx = 0;
                        for (int i = 0; i < entries.length(); i++) {
                            JSONObject e = entries.getJSONObject(i);
                            if (e.getJSONObject("team").getString("displayName").contains("Flamengo")) {
                                flaIdx = i;
                                break;
                            }
                        }
                        int start = Math.max(0, Math.min(flaIdx - 3, entries.length() - 8));
                        JSONArray window = new JSONArray();
                        for (int i = start; i < start + 8 && i < entries.length(); i++) {
                            JSONObject e = entries.getJSONObject(i);
                            JSONObject team = e.getJSONObject("team");
                            JSONArray stats = e.optJSONArray("stats");
                            String rk = "", pt = "", gp = "", w = "", diff = "";
                            for (int k = 0; stats != null && k < stats.length(); k++) {
                                JSONObject st = stats.getJSONObject(k);
                                String sn = st.optString("name");
                                if ("rank".equals(sn)) rk = st.optString("displayValue");
                                else if ("points".equals(sn)) pt = st.optString("displayValue");
                                else if ("gamesPlayed".equals(sn)) gp = st.optString("displayValue");
                                else if ("wins".equals(sn)) w = st.optString("displayValue");
                                else if ("pointDifferential".equals(sn)) diff = st.optString("displayValue");
                            }
                            JSONObject row = new JSONObject();
                            row.put("rank", rk);
                            row.put("teamId", team.optString("id"));
                            row.put("name", team.optString("shortDisplayName", team.optString("displayName")));
                            row.put("points", pt);
                            row.put("played", gp);
                            row.put("wins", w.isEmpty() ? "0" : w);
                            row.put("goalDiff", diff.replace("+", ""));
                            row.put("isFla", team.optString("displayName").contains("Flamengo"));
                            window.put(row);
                        }
                        payload.put("standings", window);
                    }
                }

                // 2. Schedule & Past Matches
                String scRaw = fetchHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/bra.1/teams/819/schedule");
                if (scRaw != null) {
                    JSONObject scObj = new JSONObject(scRaw);
                    JSONArray events = scObj.optJSONArray("events");
                    JSONArray pastArr = new JSONArray();
                    for (int i = 0; events != null && i < events.length() && pastArr.length() < 5; i++) {
                        JSONObject ev = events.getJSONObject(i);
                        JSONArray comps = ev.optJSONArray("competitions");
                        if (comps == null || comps.length() == 0) continue;
                        JSONObject comp = comps.getJSONObject(0);
                        JSONObject status = comp.optJSONObject("status");
                        if (status != null && status.optJSONObject("type") != null && status.getJSONObject("type").optBoolean("completed", false)) {
                            JSONArray teams = comp.optJSONArray("competitors");
                            JSONObject fla = null, opp = null;
                            for (int t = 0; teams != null && t < teams.length(); t++) {
                                JSONObject tm = teams.getJSONObject(t);
                                if ("819".equals(tm.optString("id"))) fla = tm;
                                else opp = tm;
                            }
                            if (fla != null && opp != null) {
                                String flaScore = extractScore(fla);
                                String oppScore = extractScore(opp);
                                boolean isHome = "home".equals(fla.optString("homeAway"));
                                boolean win = fla.optBoolean("winner", false);
                                String res = win ? "V" : (flaScore.equals(oppScore) ? "E" : "D");
                                String oppName = opp.optJSONObject("team") != null ? opp.getJSONObject("team").optString("shortDisplayName", opp.getJSONObject("team").optString("displayName")) : "Adversário";
                                String oppId = opp.optJSONObject("team") != null ? opp.getJSONObject("team").optString("id") : "";
                                String score = isHome ? flaScore + "x" + oppScore : oppScore + "x" + flaScore;
                                String query = isHome
                                        ? "Flamengo " + flaScore + " x " + oppScore + " " + oppName + " melhores momentos ge"
                                        : oppName + " " + oppScore + " x " + flaScore + " Flamengo melhores momentos ge";
                                String videoId = resolveYoutubeVideoId(query);
                                if (videoId.isEmpty()) videoId = getFallbackVideoId(oppName);
                                JSONObject item = new JSONObject();
                                item.put("res", res);
                                item.put("oppName", oppName);
                                item.put("oppId", oppId);
                                item.put("isHome", isHome);
                                item.put("score", score);
                                item.put("tourn", "Brasileirão 2026");
                                item.put("videoId", videoId);
                                pastArr.put(item);
                            }
                        }
                    }
                    if (pastArr.length() > 0) payload.put("past", pastArr);
                }

                // Next matches list (5 fixtures)
                JSONArray nextArr = new JSONArray();
                JSONObject n1 = new JSONObject(); n1.put("home", "Flamengo"); n1.put("away", "Estudiantes"); n1.put("homeId", "819"); n1.put("awayId", "estudiantes"); n1.put("date", "22/10 21:30"); n1.put("tourn", "Libertadores 2026"); nextArr.put(n1);
                JSONObject n2 = new JSONObject(); n2.put("home", "Flamengo"); n2.put("away", "Atlético-MG"); n2.put("homeId", "819"); n2.put("awayId", "7632"); n2.put("date", "26/10 20:00"); n2.put("tourn", "Brasileirão 2026"); nextArr.put(n2);
                JSONObject n3 = new JSONObject(); n3.put("home", "Vasco"); n3.put("away", "Flamengo"); n3.put("homeId", "3454"); n3.put("awayId", "819"); n3.put("date", "29/10 20:00"); n3.put("tourn", "Brasileirão 2026"); nextArr.put(n3);
                JSONObject n4 = new JSONObject(); n4.put("home", "Grêmio"); n4.put("away", "Flamengo"); n4.put("homeId", "6273"); n4.put("awayId", "819"); n4.put("date", "04/11 19:30"); n4.put("tourn", "Brasileirão 2026"); nextArr.put(n4);
                JSONObject n5 = new JSONObject(); n5.put("home", "Flamengo"); n5.put("away", "Cruzeiro"); n5.put("homeId", "819"); n5.put("awayId", "2022"); n5.put("date", "08/11 21:00"); n5.put("tourn", "Brasileirão 2026"); nextArr.put(n5);
                payload.put("next", nextArr);

                final String full = payload.toString();
                prefs.edit().putString("payload", full).putLong("last_fetch", System.currentTimeMillis()).apply();
                main.post(() -> {
                    parsePayload(full);
                    fetching = false;
                    lastFetch = System.currentTimeMillis();
                    if (onDone != null) onDone.run();
                });
            } catch (Exception e) {
                main.post(() -> {
                    fetching = false;
                    if (onDone != null) onDone.run();
                });
            }
        });
    }

    private static String extractScore(JSONObject comp) {
        Object sc = comp.opt("score");
        if (sc instanceof JSONObject) return ((JSONObject) sc).optString("displayValue", "0");
        return String.valueOf(sc != null ? sc : "0");
    }

    private void parsePayload(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray sArr = obj.optJSONArray("standings");
            if (sArr != null && sArr.length() > 0) {
                standings.clear();
                for (int i = 0; i < sArr.length(); i++) {
                    JSONObject r = sArr.getJSONObject(i);
                    standings.add(new StandingRow(r.optString("rank"), r.optString("teamId"), r.optString("name"), r.optString("points"), r.optString("played"), r.optString("wins"), r.optString("goalDiff"), r.optBoolean("isFla")));
                }
            }
            JSONArray pArr = obj.optJSONArray("past");
            if (pArr != null && pArr.length() > 0) {
                lastResults.clear();
                for (int i = 0; i < pArr.length(); i++) {
                    JSONObject p = pArr.getJSONObject(i);
                    String vid = p.optString("videoId", "");
                    if (vid.isEmpty()) vid = getFallbackVideoId(p.optString("oppName"));
                    lastResults.add(new MatchResult(p.optString("res"), p.optString("oppName"), p.optString("oppId"), p.optBoolean("isHome"), p.optString("score"), p.optString("tourn", "Brasileirão 2026"), vid));
                }
            }
            JSONArray nArr = obj.optJSONArray("next");
            if (nArr != null && nArr.length() > 0) {
                nextFixtures.clear();
                for (int i = 0; i < nArr.length(); i++) {
                    JSONObject n = nArr.getJSONObject(i);
                    nextFixtures.add(new Fixture(n.optString("home"), n.optString("away"), n.optString("homeId"), n.optString("awayId"), n.optString("date"), n.optString("tourn")));
                }
            }
        } catch (Exception ignored) {}
    }

    public static String resolveYoutubeVideoId(String query) {
        try {
            String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
            String html = fetchHttp(url);
            if (html != null) {
                Matcher m = YT_VIDEO_ID_PATTERN.matcher(html);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String getFallbackVideoId(String oppName) {
        if (oppName == null) return "4-LPaBTA068";
        String lower = oppName.toLowerCase();
        if (lower.contains("bahia")) return "4-LPaBTA068";
        if (lower.contains("fluminense")) return "NZCLBDNb-o0";
        if (lower.contains("santos")) return "_Y5B3JOtdYI";
        if (lower.contains("bragantino")) return "Qw19l7_Tm1Q";
        if (lower.contains("corinthians")) return "oxXjXSwQ40w";
        if (lower.contains("botafogo")) return "FZ5_oGQtbzc";
        if (lower.contains("cruzeiro")) return "sSiSTWgaJZk";
        if (lower.contains("vitória") || lower.contains("vitoria")) return "fIMnEts7U-c";
        if (lower.contains("internacional")) return "udLyHeD2pOk";
        if (lower.contains("são paulo") || lower.contains("sao paulo")) return "6kVcqMqFz7I";
        return "4-LPaBTA068";
    }

    public static void launchMatchHighlights(Context context, MatchResult match) {
        if (context == null || match == null) return;
        String videoId = (match.videoId != null && !match.videoId.isEmpty())
                ? match.videoId
                : getFallbackVideoId(match.oppName);
        Uri uri = Uri.parse("https://www.youtube.com/watch?v=" + videoId);
        String[] tvPackages = {
                "org.smarttube.stable",
                "org.smarttube.beta",
                "com.liskovsoft.smarttubetv.beta",
                "com.liskovsoft.smarttubetv",
                "com.google.android.youtube.tv"
        };
        for (String pkg : tvPackages) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.setPackage(pkg);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        try {
            Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(fallback);
        } catch (Exception ignored) {}
    }

    private static String fetchHttp(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
