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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data and asset manager for the optional full-screen Club tab dashboard:
 * - Standings window (8 teams centered around the configured club)
 * - Last 5 completed matches across all competitions with YouTube highlight links
 * - Next 5 upcoming (or live) fixtures converted to the TV's local time zone
 * - Cached crests from bundled assets plus background ESPN logo downloads for new opponents
 */
final class ClubData {
    static final class Profile {
        final String key;
        final String teamId;
        final String shortName;
        final String fullName;
        final int accentColor;
        final int rowStartColor;
        final int rowEndColor;
        final int glowTopColor;
        final int glowBotColor;
        final int emberPrimary;
        final int emberSecondary;

        Profile(String key, String teamId, String shortName, String fullName,
                int accentColor, int rowStartColor, int rowEndColor,
                int glowTopColor, int glowBotColor, int emberPrimary, int emberSecondary) {
            this.key = key;
            this.teamId = teamId;
            this.shortName = shortName;
            this.fullName = fullName;
            this.accentColor = accentColor;
            this.rowStartColor = rowStartColor;
            this.rowEndColor = rowEndColor;
            this.glowTopColor = glowTopColor;
            this.glowBotColor = glowBotColor;
            this.emberPrimary = emberPrimary;
            this.emberSecondary = emberSecondary;
        }
    }

    static final class Fixture {
        final String homeTeam;
        final String awayTeam;
        final String homeId;
        final String awayId;
        final String dateTime;   // "18/11 20:30" or "AO VIVO 1-0"
        final String tournament;
        final boolean live;

        Fixture(String homeTeam, String awayTeam, String homeId, String awayId,
                String dateTime, String tournament, boolean live) {
            this.homeTeam = homeTeam;
            this.awayTeam = awayTeam;
            this.homeId = homeId;
            this.awayId = awayId;
            this.dateTime = dateTime;
            this.tournament = tournament;
            this.live = live;
        }

        String datePart() {
            if (live) return "AO VIVO";
            if (dateTime == null) return "";
            int sp = dateTime.indexOf(' ');
            return sp > 0 ? dateTime.substring(0, sp) : dateTime;
        }

        String timePart() {
            if (dateTime == null) return "";
            int sp = dateTime.indexOf(' ');
            return sp > 0 ? dateTime.substring(sp + 1) : "";
        }
    }

    static final class MatchResult {
        final String result; // "V", "E", "D"
        final String clubName;
        final String clubId;
        final String oppName;
        final String oppId;
        final boolean isHome;
        final String scoreText; // "0x3", "1x0", etc.
        final String tournament;
        final String videoId;

        MatchResult(String result, String clubName, String clubId, String oppName, String oppId,
                    boolean isHome, String scoreText, String tournament, String videoId) {
            this.result = result;
            this.clubName = clubName;
            this.clubId = clubId;
            this.oppName = oppName;
            this.oppId = oppId;
            this.isHome = isHome;
            this.scoreText = scoreText;
            this.tournament = (tournament != null && !tournament.isEmpty()) ? tournament : "Brasileirão";
            this.videoId = videoId != null ? videoId : "";
        }

        String homeTeam() {
            return isHome ? clubName : oppName;
        }

        String awayTeam() {
            return isHome ? oppName : clubName;
        }

        String homeId() {
            return isHome ? clubId : oppId;
        }

        String awayId() {
            return isHome ? oppId : clubId;
        }

        String formattedScore() {
            if (scoreText == null) return "0 - 0";
            return scoreText.replace("x", " - ").replace("X", " - ");
        }
    }

    static final class StandingRow {
        final String rank;
        final String teamId;
        final String name;
        final String points;
        final String played;
        final String wins;
        final String goalDiff;
        final boolean isClub;

        StandingRow(String rank, String teamId, String name, String points,
                    String played, String wins, String goalDiff, boolean isClub) {
            this.rank = rank.replace("º", "");
            this.teamId = teamId;
            this.name = name;
            this.points = points;
            this.played = played;
            this.wins = wins;
            this.goalDiff = goalDiff;
            this.isClub = isClub;
        }
    }

    private static final Map<String, Profile> PROFILES = new HashMap<>();
    static {
        addProfile("flamengo", "819", "Flamengo", "Clube de Regatas do Flamengo",
                0xFFD92332, 0xCC6E0913, 0xB846050C, 0x489A0B1B, 0x337A0815, 0xFFFF2A3C, 0xFFFFB703);
        addProfile("palmeiras", "2029", "Palmeiras", "Sociedade Esportiva Palmeiras",
                0xFF22C55E, 0xCC064E3B, 0xB8032E22, 0x48056B45, 0x33044E33, 0xFF22C55E, 0xFFE2E8F0);
        addProfile("corinthians", "874", "Corinthians", "Sport Club Corinthians Paulista",
                0xFFE2E8F0, 0xCC27272A, 0xB818181B, 0x44475569, 0x33334155, 0xFFFFFFFF, 0xFF94A3B8);
        addProfile("sao-paulo", "2026", "São Paulo", "São Paulo Futebol Clube",
                0xFFEF4444, 0xCC7F1D1D, 0xB827272A, 0x48B91C1C, 0x3352525B, 0xFFEF4444, 0xFFFFFFFF);
        addProfile("vasco", "3454", "Vasco", "Club de Regatas Vasco da Gama",
                0xFFE2E8F0, 0xCC27272A, 0xB818181B, 0x4452525B, 0x3327272A, 0xFFFFFFFF, 0xFFEF4444);
        addProfile("fluminense", "3445", "Fluminense", "Fluminense Football Club",
                0xFFFB7185, 0xCC7F1D1D, 0xB8064E3B, 0x489F1239, 0x33065F46, 0xFFF43F5E, 0xFF10B981);
        addProfile("botafogo", "6086", "Botafogo", "Botafogo de Futebol e Regatas",
                0xFFE2E8F0, 0xCC27272A, 0xB818181B, 0x44475569, 0x331E293B, 0xFFFFFFFF, 0xFFCBD5E1);
        addProfile("gremio", "6273", "Grêmio", "Grêmio Foot-Ball Porto Alegrense",
                0xFF38BDF8, 0xCC0369A1, 0xB80C4A6E, 0x480284C7, 0x33075985, 0xFF38BDF8, 0xFFFFFFFF);
        addProfile("internacional", "1936", "Internacional", "Sport Club Internacional",
                0xFFEF4444, 0xCC7F1D1D, 0xB8450A0A, 0x48B91C1C, 0x337F1D1D, 0xFFEF4444, 0xFFFFFFFF);
        addProfile("cruzeiro", "2022", "Cruzeiro", "Cruzeiro Esporte Clube",
                0xFF60A5FA, 0xCC1D4ED8, 0xB81E3A8A, 0x482563EB, 0x331E40AF, 0xFF60A5FA, 0xFFFFFFFF);
        addProfile("atletico-mg", "7632", "Atlético-MG", "Clube Atlético Mineiro",
                0xFFE2E8F0, 0xCC27272A, 0xB818181B, 0x44475569, 0x3327272A, 0xFFFFFFFF, 0xFF94A3B8);
        addProfile("bahia", "9967", "Bahia", "Esporte Clube Bahia",
                0xFF60A5FA, 0xCC1D4ED8, 0xB87F1D1D, 0x482563EB, 0x33991B1B, 0xFF3B82F6, 0xFFEF4444);
        addProfile("athletico-pr", "3458", "Athletico-PR", "Club Athletico Paranaense",
                0xFFEF4444, 0xCC7F1D1D, 0xB818181B, 0x48B91C1C, 0x3327272A, 0xFFEF4444, 0xFF94A3B8);
        addProfile("santos", "2674", "Santos", "Santos Futebol Clube",
                0xFFE2E8F0, 0xCC334155, 0xB81E293B, 0x4464748B, 0x33334155, 0xFFFFFFFF, 0xFFFACC15);
        addProfile("bragantino", "6079", "Bragantino", "Red Bull Bragantino",
                0xFFF43F5E, 0xCC9F1239, 0xB81E293B, 0x48E11D48, 0x33334155, 0xFFF43F5E, 0xFFFACC15);
        addProfile("coritiba", "3456", "Coritiba", "Coritiba Foot Ball Club",
                0xFF22C55E, 0xCC15803D, 0xB8052E16, 0x4816A34A, 0x33064E3B, 0xFF22C55E, 0xFFFFFFFF);
        addProfile("vitoria", "3457", "Vitória", "Esporte Clube Vitória",
                0xFFEF4444, 0xCC991B1B, 0xB818181B, 0x48DC2626, 0x3327272A, 0xFFEF4444, 0xFF94A3B8);
        addProfile("mirassol", "9169", "Mirassol", "Mirassol Futebol Clube",
                0xFFFACC15, 0xCC854D0E, 0xB814532D, 0x48CA8A04, 0x33166534, 0xFFFACC15, 0xFF22C55E);
        addProfile("remo", "4936", "Remo", "Clube do Remo",
                0xFF60A5FA, 0xCC1E3A8A, 0xB80F172A, 0x481D4ED8, 0x330F172A, 0xFF60A5FA, 0xFFFFFFFF);
        addProfile("chapecoense", "9318", "Chapecoense", "Associação Chapecoense de Futebol",
                0xFF22C55E, 0xCC15803D, 0xB8052E16, 0x4816A34A, 0x33064E3B, 0xFF22C55E, 0xFFFFFFFF);
    }

    private static void addProfile(String key, String teamId, String shortName, String fullName,
                                   int accentColor, int rowStart, int rowEnd,
                                   int glowTop, int glowBot, int ember1, int ember2) {
        PROFILES.put(key, new Profile(key, teamId, shortName, fullName,
                accentColor, rowStart, rowEnd, glowTop, glowBot, ember1, ember2));
    }

    private static Profile resolveProfile() {
        String raw = Config.TEAM == null ? "" : Config.TEAM.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || "none".equals(raw) || "off".equals(raw) || "false".equals(raw)) {
            return null;
        }
        String norm = Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        Profile exact = PROFILES.get(norm);
        if (exact != null) return exact;
        for (Profile p : PROFILES.values()) {
            if (p.teamId.equals(raw) || p.key.replace("-", "").equals(norm.replace("-", ""))) {
                return p;
            }
        }
        if (raw.matches("\\d+")) {
            return new Profile(raw, raw, "Clube", "Brasileirão",
                    0xFF60A5FA, 0xCC1D4ED8, 0xB81E3A8A, 0x482563EB, 0x331E40AF, 0xFF60A5FA, 0xFFFFFFFF);
        }
        return PROFILES.get("flamengo");
    }

    private static final Profile ACTIVE_PROFILE = resolveProfile();

    static boolean isEnabled() {
        return ACTIVE_PROFILE != null;
    }

    static Profile activeProfile() {
        return ACTIVE_PROFILE;
    }

    private static ClubData instance;
    private static final long REFRESH_INTERVAL = 15 * 60_000L;
    private static final long LIVE_REFRESH_INTERVAL = 60_000L;
    private static final Pattern YT_VIDEO_ID_PATTERN = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"");

    static synchronized ClubData get(Context c) {
        if (instance == null) instance = new ClubData(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Profile profile;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService crestIo = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> crestCache = new HashMap<>();
    private final Map<String, String> logoUrls = new HashMap<>();
    private final Set<String> fetchingCrests = new HashSet<>();
    private final List<Runnable> waiters = new ArrayList<>();
    private Bitmap clubCrest;
    private long lastFetch;
    private boolean fetching;
    private boolean liveMatch;

    final List<Fixture> nextFixtures = new ArrayList<>();
    final List<MatchResult> lastResults = new ArrayList<>();
    final List<StandingRow> standings = new ArrayList<>();

    private ClubData(Context c) {
        this.context = c.getApplicationContext();
        this.profile = ACTIVE_PROFILE != null ? ACTIVE_PROFILE : PROFILES.get("flamengo");
        this.prefs = context.getSharedPreferences("club_dashboard_v3_" + profile.teamId, Context.MODE_PRIVATE);
        loadAssets();
        loadCached();
    }

    private void loadAssets() {
        clubCrest = loadPngAsset("flamengo/teams/" + profile.teamId + ".png");
        if (clubCrest == null && "819".equals(profile.teamId)) {
            clubCrest = loadPngAsset("flamengo/flamengo_shield.png");
        }
    }

    private Bitmap loadPngAsset(String path) {
        try (InputStream in = context.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
    }

    Bitmap getClubCrest() {
        if (clubCrest != null) return clubCrest;
        return getTeamCrest(profile.teamId);
    }

    synchronized Bitmap getTeamCrest(String teamId) {
        if (teamId == null || teamId.isEmpty()) return null;
        if (profile.teamId.equals(teamId) && clubCrest != null) return clubCrest;
        Bitmap mem = crestCache.get(teamId);
        if (mem != null) return mem;

        Bitmap asset = loadPngAsset("flamengo/teams/" + teamId + ".png");
        if (asset != null) {
            crestCache.put(teamId, asset);
            return asset;
        }

        File cached = crestFile(teamId);
        if (cached.exists()) {
            Bitmap disk = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (disk != null) {
                crestCache.put(teamId, disk);
                return disk;
            }
        }

        String url = logoUrls.get(teamId);
        if (url != null && !url.isEmpty() && !fetchingCrests.contains(teamId)) {
            fetchingCrests.add(teamId);
            crestIo.execute(() -> downloadCrest(teamId, url));
        }
        return null;
    }

    private File crestFile(String teamId) {
        File dir = new File(context.getCacheDir(), "crests");
        if (!dir.exists()) dir.mkdirs();
        String safe = teamId.replaceAll("[^a-zA-Z0-9_-]", "");
        return new File(dir, safe + ".png");
    }

    private void downloadCrest(String teamId, String urlStr) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "FreeMyTV");
            if (conn.getResponseCode() == 200) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = conn.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                byte[] bytes = out.toByteArray();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp != null) {
                    try (FileOutputStream fos = new FileOutputStream(crestFile(teamId))) {
                        fos.write(bytes);
                    }
                    synchronized (this) {
                        crestCache.put(teamId, bmp);
                        if (profile.teamId.equals(teamId) && clubCrest == null) clubCrest = bmp;
                    }
                    main.post(this::notifyWaitersIfAny);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
            synchronized (this) {
                fetchingCrests.remove(teamId);
            }
        }
    }

    private void loadCached() {
        String json = prefs.getString("payload", null);
        lastFetch = prefs.getLong("last_fetch", 0);
        if (json != null) {
            parsePayload(json);
        }
    }

    void refresh(final Runnable onDone) {
        refreshIfStale(onDone);
    }

    synchronized void refreshIfStale(final Runnable onDone) {
        if (!isEnabled()) {
            if (onDone != null) onDone.run();
            return;
        }
        long now = System.currentTimeMillis();
        long interval = liveMatch ? LIVE_REFRESH_INTERVAL : REFRESH_INTERVAL;
        boolean clockRolledBack = now < lastFetch;
        boolean emptyData = standings.isEmpty() || (lastResults.isEmpty() && nextFixtures.isEmpty());
        if (!fetching && !emptyData && !clockRolledBack && (now - lastFetch < interval)) {
            if (onDone != null) onDone.run();
            return;
        }
        if (onDone != null && !waiters.contains(onDone)) {
            waiters.add(onDone);
        }
        if (fetching) return;
        fetching = true;
        io.execute(this::fetchAll);
    }

    private void notifyWaitersIfAny() {
        List<Runnable> copy;
        synchronized (this) {
            copy = new ArrayList<>(waiters);
            waiters.clear();
        }
        for (Runnable r : copy) {
            try { r.run(); } catch (Exception ignored) {}
        }
    }

    private static final class ParsedEvent implements Comparable<ParsedEvent> {
        final long epochMs;
        final JSONObject event;

        ParsedEvent(long epochMs, JSONObject event) {
            this.epochMs = epochMs;
            this.event = event;
        }

        @Override public int compareTo(ParsedEvent o) {
            return Long.compare(this.epochMs, o.epochMs);
        }
    }

    private void fetchAll() {
        boolean anySuccess = false;
        boolean foundLive = false;
        try {
            JSONObject payload = new JSONObject();
            String existing = prefs.getString("payload", null);
            if (existing != null) {
                try { payload = new JSONObject(existing); } catch (Exception ignored) {}
            }

            // 1. Standings (8 teams around configured club in Brasileirao)
            String sRaw = fetchHttp("https://site.api.espn.com/apis/v2/sports/soccer/bra.1/standings");
            if (sRaw != null) {
                JSONObject sObj = new JSONObject(sRaw);
                JSONArray children = sObj.optJSONArray("children");
                if (children != null && children.length() > 0) {
                    JSONArray entries = children.getJSONObject(0).getJSONObject("standings").getJSONArray("entries");
                    int clubIdx = 0;
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject e = entries.getJSONObject(i);
                        JSONObject tm = e.getJSONObject("team");
                        rememberLogo(tm);
                        if (profile.teamId.equals(tm.optString("id"))) {
                            clubIdx = i;
                        }
                    }
                    int start = Math.max(0, Math.min(clubIdx - 3, entries.length() - 8));
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
                        row.put("isClub", profile.teamId.equals(team.optString("id")));
                        window.put(row);
                    }
                    payload.put("standings", window);
                    anySuccess = true;
                }
            }

            // 2. Past Matches across ALL competitions sorted newest-first
            String scRaw = fetchHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/" + profile.teamId + "/schedule");
            if (scRaw != null) {
                JSONObject scObj = new JSONObject(scRaw);
                JSONArray events = scObj.optJSONArray("events");
                List<ParsedEvent> completed = new ArrayList<>();
                for (int i = 0; events != null && i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    JSONArray comps = ev.optJSONArray("competitions");
                    if (comps == null || comps.length() == 0) continue;
                    JSONObject comp = comps.getJSONObject(0);
                    JSONObject status = comp.optJSONObject("status");
                    JSONObject type = status != null ? status.optJSONObject("type") : null;
                    if (type != null && type.optBoolean("completed", false)) {
                        completed.add(new ParsedEvent(parseIsoUtc(ev.optString("date")), ev));
                    }
                }
                Collections.sort(completed, (a, b) -> Long.compare(b.epochMs, a.epochMs));
                JSONArray pastArr = new JSONArray();
                for (int i = 0; i < completed.size() && pastArr.length() < 5; i++) {
                    JSONObject ev = completed.get(i).event;
                    JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
                    JSONArray teams = comp.optJSONArray("competitors");
                    JSONObject club = null, opp = null;
                    for (int t = 0; teams != null && t < teams.length(); t++) {
                        JSONObject tm = teams.getJSONObject(t);
                        rememberLogo(tm.optJSONObject("team"));
                        if (profile.teamId.equals(tm.optString("id"))) club = tm;
                        else opp = tm;
                    }
                    if (club != null && opp != null) {
                        String clubScore = extractScore(club);
                        String oppScore = extractScore(opp);
                        boolean isHome = "home".equals(club.optString("homeAway"));
                        String res = computeResult(club, opp, clubScore, oppScore);
                        JSONObject oppTeam = opp.optJSONObject("team");
                        String oppName = oppTeam != null ? oppTeam.optString("shortDisplayName", oppTeam.optString("displayName")) : "Adversário";
                        String oppId = oppTeam != null ? oppTeam.optString("id") : "";
                        String score = isHome ? clubScore + "x" + oppScore : oppScore + "x" + clubScore;
                        String eventId = ev.optString("id", "");
                        String ytKey = "yt_" + eventId;
                        String videoId = eventId.isEmpty() ? "" : prefs.getString(ytKey, "");
                        if (videoId.isEmpty()) {
                            String query = isHome
                                    ? profile.shortName + " " + clubScore + " x " + oppScore + " " + oppName + " melhores momentos ge"
                                    : oppName + " " + oppScore + " x " + clubScore + " " + profile.shortName + " melhores momentos ge";
                            videoId = resolveYoutubeVideoId(query);
                            if (!videoId.isEmpty() && !eventId.isEmpty()) {
                                prefs.edit().putString(ytKey, videoId).apply();
                            }
                        }
                        JSONObject item = new JSONObject();
                        item.put("res", res);
                        item.put("oppName", oppName);
                        item.put("oppId", oppId);
                        item.put("isHome", isHome);
                        item.put("score", score);
                        item.put("tourn", formatTournament(ev));
                        item.put("videoId", videoId);
                        pastArr.put(item);
                    }
                }
                if (pastArr.length() > 0) {
                    payload.put("past", pastArr);
                    anySuccess = true;
                }
            }

            // 3. Upcoming (and live) fixtures across ALL competitions sorted soonest-first
            String fxRaw = fetchHttp("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/" + profile.teamId + "/schedule?fixture=true");
            if (fxRaw != null) {
                JSONObject fxObj = new JSONObject(fxRaw);
                JSONArray events = fxObj.optJSONArray("events");
                List<ParsedEvent> upcoming = new ArrayList<>();
                for (int i = 0; events != null && i < events.length(); i++) {
                    JSONObject ev = events.getJSONObject(i);
                    JSONArray comps = ev.optJSONArray("competitions");
                    if (comps == null || comps.length() == 0) continue;
                    JSONObject comp = comps.getJSONObject(0);
                    JSONObject status = comp.optJSONObject("status");
                    JSONObject type = status != null ? status.optJSONObject("type") : null;
                    boolean completed = type != null && type.optBoolean("completed", false);
                    if (!completed) {
                        upcoming.add(new ParsedEvent(parseIsoUtc(ev.optString("date")), ev));
                    }
                }
                Collections.sort(upcoming);
                JSONArray nextArr = new JSONArray();
                for (int i = 0; i < upcoming.size() && nextArr.length() < 5; i++) {
                    ParsedEvent pe = upcoming.get(i);
                    JSONObject ev = pe.event;
                    JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
                    JSONArray teams = comp.optJSONArray("competitors");
                    JSONObject home = null, away = null;
                    for (int t = 0; teams != null && t < teams.length(); t++) {
                        JSONObject tm = teams.getJSONObject(t);
                        rememberLogo(tm.optJSONObject("team"));
                        if ("home".equals(tm.optString("homeAway"))) home = tm;
                        else away = tm;
                    }
                    if (home == null && teams != null && teams.length() >= 2) {
                        home = teams.getJSONObject(0);
                        away = teams.getJSONObject(1);
                    }
                    if (home == null || away == null) continue;
                    JSONObject homeTeam = home.optJSONObject("team");
                    JSONObject awayTeam = away.optJSONObject("team");
                    String homeName = homeTeam != null ? homeTeam.optString("shortDisplayName", homeTeam.optString("displayName")) : "";
                    String awayName = awayTeam != null ? awayTeam.optString("shortDisplayName", awayTeam.optString("displayName")) : "";
                    String homeId = homeTeam != null ? homeTeam.optString("id") : "";
                    String awayId = awayTeam != null ? awayTeam.optString("id") : "";
                    JSONObject status = comp.optJSONObject("status");
                    JSONObject type = status != null ? status.optJSONObject("type") : null;
                    boolean isLive = type != null && "in".equals(type.optString("state"));
                    if (isLive) foundLive = true;
                    String dateStr;
                    if (isLive) {
                        dateStr = "AO VIVO " + extractScore(home) + "-" + extractScore(away);
                    } else {
                        boolean timeValid = comp.optBoolean("timeValid", ev.optBoolean("timeValid", true));
                        dateStr = formatLocalFixtureDate(pe.epochMs, timeValid);
                    }
                    JSONObject item = new JSONObject();
                    item.put("home", homeName);
                    item.put("away", awayName);
                    item.put("homeId", homeId);
                    item.put("awayId", awayId);
                    item.put("date", dateStr);
                    item.put("tourn", formatTournament(ev));
                    item.put("live", isLive);
                    nextArr.put(item);
                }
                payload.put("next", nextArr);
                anySuccess = true;
            }

            if (anySuccess) {
                long fetchedAt = System.currentTimeMillis();
                final String full = payload.toString();
                prefs.edit().putString("payload", full).putLong("last_fetch", fetchedAt).apply();
                final boolean isLiveNow = foundLive;
                main.post(() -> {
                    parsePayload(full);
                    synchronized (ClubData.this) {
                        liveMatch = isLiveNow;
                        lastFetch = fetchedAt;
                        fetching = false;
                    }
                    notifyWaitersIfAny();
                });
            } else {
                main.post(() -> {
                    synchronized (ClubData.this) {
                        fetching = false;
                    }
                    notifyWaitersIfAny();
                });
            }
        } catch (Exception e) {
            AppLog.w("ClubData refresh failed: " + e.getMessage());
            main.post(() -> {
                synchronized (ClubData.this) {
                    fetching = false;
                }
                notifyWaitersIfAny();
            });
        }
    }

    private void rememberLogo(JSONObject team) {
        if (team == null) return;
        String id = team.optString("id", "");
        if (id.isEmpty()) return;
        String url = team.optString("logo", "");
        if (url.isEmpty()) {
            JSONArray logos = team.optJSONArray("logos");
            if (logos != null && logos.length() > 0 && logos.optJSONObject(0) != null) {
                url = logos.optJSONObject(0).optString("href", "");
            }
        }
        if (!url.isEmpty()) {
            logoUrls.put(id, url);
        }
    }

    private static String computeResult(JSONObject club, JSONObject opp, String clubScore, String oppScore) {
        if (club.optBoolean("winner", false)) return "V";
        if (opp.optBoolean("winner", false)) return "D";
        try {
            int cs = Integer.parseInt(clubScore.trim());
            int os = Integer.parseInt(oppScore.trim());
            if (cs > os) return "V";
            if (cs < os) return "D";
        } catch (NumberFormatException ignored) {}
        return "E";
    }

    private static String formatTournament(JSONObject ev) {
        JSONObject league = ev.optJSONObject("league");
        JSONObject st = ev.optJSONObject("seasonType");
        JSONObject season = ev.optJSONObject("season");
        int year = season != null ? season.optInt("year", 2026) : 2026;
        String raw = "";
        if (league != null) raw = league.optString("name", "");
        if (raw.isEmpty() && st != null) raw = st.optString("name", "");
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("serie a") || lower.contains("brasileir")) return "Brasileirão " + year;
        if (lower.contains("libertadores")) return "Libertadores " + year;
        if (lower.contains("copa do brasil")) return "Copa do Brasil " + year;
        if (lower.contains("sudamericana") || lower.contains("sul-americana")) return "Sul-Americana " + year;
        if (lower.contains("carioca")) return "Carioca " + year;
        if (lower.contains("paulista")) return "Paulistão " + year;
        if (lower.contains("mineiro")) return "Mineiro " + year;
        if (lower.contains("gaucho") || lower.contains("gaúcho")) return "Gauchão " + year;
        if (lower.contains("club world") || lower.contains("intercontinental") || lower.contains("mundial")) return "Mundial " + year;
        if (!raw.isEmpty()) return raw.replaceAll("^\\d{4}\\s+", "") + " " + year;
        return "Brasileirão " + year;
    }

    private static long parseIsoUtc(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm"};
        for (String p : patterns) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(p, Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = sdf.parse(iso);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {}
        }
        return 0L;
    }

    private static String formatLocalFixtureDate(long epochMs, boolean timeValid) {
        if (epochMs <= 0L) return "";
        SimpleDateFormat fmt = new SimpleDateFormat(timeValid ? "dd/MM HH:mm" : "dd/MM", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        String s = fmt.format(new Date(epochMs));
        return timeValid ? s : s + " A def.";
    }

    private static String extractScore(JSONObject comp) {
        Object sc = comp.opt("score");
        if (sc instanceof JSONObject) return ((JSONObject) sc).optString("displayValue", "0");
        return sc != null ? String.valueOf(sc) : "0";
    }

    private void parsePayload(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONArray sArr = obj.optJSONArray("standings");
            if (sArr != null) {
                standings.clear();
                for (int i = 0; i < sArr.length(); i++) {
                    JSONObject r = sArr.getJSONObject(i);
                    boolean isClub = r.has("isClub") ? r.optBoolean("isClub") : r.optBoolean("isFla");
                    standings.add(new StandingRow(
                            r.optString("rank"), r.optString("teamId"), r.optString("name"),
                            r.optString("points"), r.optString("played"), r.optString("wins"),
                            r.optString("goalDiff"), isClub));
                }
            }
            JSONArray pArr = obj.optJSONArray("past");
            if (pArr != null) {
                lastResults.clear();
                for (int i = 0; i < pArr.length(); i++) {
                    JSONObject p = pArr.getJSONObject(i);
                    lastResults.add(new MatchResult(
                            p.optString("res"), profile.shortName, profile.teamId,
                            p.optString("oppName"), p.optString("oppId"),
                            p.optBoolean("isHome"), p.optString("score"),
                            p.optString("tourn", "Brasileirão"), p.optString("videoId", "")));
                }
            }
            JSONArray nArr = obj.optJSONArray("next");
            if (nArr != null) {
                nextFixtures.clear();
                for (int i = 0; i < nArr.length(); i++) {
                    JSONObject n = nArr.getJSONObject(i);
                    nextFixtures.add(new Fixture(
                            n.optString("home"), n.optString("away"),
                            n.optString("homeId"), n.optString("awayId"),
                            n.optString("date"), n.optString("tourn"),
                            n.optBoolean("live", false)));
                }
            }
        } catch (Exception ignored) {}
    }

    static String resolveYoutubeVideoId(String query) {
        try {
            String url = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8");
            String html = fetchHttp(url);
            if (html != null) {
                Matcher m = YT_VIDEO_ID_PATTERN.matcher(html);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    static void launchMatchHighlights(Context context, MatchResult match) {
        if (context == null || match == null) return;
        Uri uri;
        if (match.videoId != null && !match.videoId.isEmpty()) {
            uri = Uri.parse("https://www.youtube.com/watch?v=" + match.videoId);
        } else {
            String q = match.homeTeam() + " x " + match.awayTeam() + " melhores momentos ge";
            try {
                uri = Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"));
            } catch (Exception e) {
                return;
            }
        }
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
