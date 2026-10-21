package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen, styled after the Apple TV app with the macOS 27 glass:
 *  - at the top left, the "For You" title and the tabs (Explore, Movies, Shows); at the top right, icon-only glass
 *    buttons (Live TV, HDMI Inputs, Settings), with the focused one's name below it, and the small crypto quotes
 *    (logo + price) at the right end of the tab row;
 *  - three cards with the titles recommended for right now in the selected tab (Recs, from TMDB); the background is
 *    the first card's photo, heavily blurred and almost white (Backdrop); without one, a light gradient;
 *  - at the bottom, the glass shelf with the apps (icons in each brand's color, like the tvOS Dock; the focused one
 *    grows);
 *  - ↓ on the shelf: everything moves up and the bottom drawer appears (inputs, shortcuts, weather for the next hours
 *    and quotes with the 24 h change); the background lightens (darkens at night). ↑ or Back closes it;
 *  - from sunset to sunrise, a night look: dark background, smoked glass and white text.
 * Measurements are in a 1920×1080 space (k = width / 1920). The screen is split into layers so the TV's weak GPU only
 * redraws what changed.
 */
final class HomeView extends ViewGroup implements Choreographer.FrameCallback, HomeData.Listener, Recs.Listener {
    // tvOS grid (Human Interface Guidelines): content inside the safe area, 80 px from the sides and 60 from top and
    // bottom; 40 px between items; 7 columns of 217 px (the apps). The shelf glass extends 36 px past the tiles (the
    // same padding on all four sides) and the tile corners are concentric with it (glass radius = radius + padding).
    private static final float MARGIN = 80, CONTENT_R = 1840;
    // Hero, anchored at the bottom (a two-line title grows upward).
    private static final float HERO_TITLE_W = 900;
    // Shelf: tiles with corners concentric with the glass (glass radius = tile radius + padding).
    // Shelf like the tvOS 27 Dock: 5:3 icons (210 × 126) in each brand's color, 48 apart, and the glass bar hugging
    // them with 40 of padding (the focused icon grows 20% without touching its edge).
    private static final float SHELF_PAD = 40, TILE_GAP = 48;
    private static final float TILE_RADIUS = 22, TILE_MAX_W = 270;
    // Tile focus (Apple TV style): it grows from the center and its shadow drops (it "rises" toward the viewer).
    private static final float FOCUS_GROW = 0.2f;
    // Corner capsule.
    // Corner capsule: the height and top offset of the tvOS tab bar (68 and 46).
    private static final float CAPSULE_TOP = 46, CAPSULE_H = 68, BUTTON = 56;
    // Bottom drawer (measured with it open; everything moves up by SHIFT).
    private static final float SHIFT = 560, ROW_TITLE = 518, ROW_TOP = 540, ROW_H = 126, CARDS_TOP = 708, CARDS_H = 300;
    // "All Apps" sheet.
    private static final int COLUMNS = 5, VISIBLE_ROWS = 3;
    private static final float GRID_W = 300, GRID_H = 169, GRID_GAP = 40, GRID_ROW = 239, GRID_TOP = 196, GRID_RADIUS = 22;
    private static final int SMOKE = 0xFF111113, INK = 0xFF1C1C1E, WHITE = 0xFFF5F5F7;
    private static final int UP_COLOR = 0xFF30D158, DOWN_COLOR = 0xFFFF453A;
    private static final long TOAST_MS = 6000, FADE_MS = 800;
    private static final int SKY = 0, HERO = 1, STATUS = 2, PLATTER = 3, TILES = 4, DRAWER = 5, SHEET = 6, TOAST = 7;
    private static final int Z_STATUS = 0, Z_HERO = 1, Z_SHELF = 2, Z_DRAWER = 3;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    // Light design (the L_ constants): title, tab bar, three recommendation cards and the shelf.
    // The tab bar is a 68 px capsule; the cards sit on the 5-column grid (the wide one spans 3).
    private static final float L_TITLE_BASE = 130, L_TABBAR_TOP = 158, L_TABBAR_H = 68, L_CARDS_TOP = 262, L_CARD_H = 442;
    private static final float L_TALL_W = 320, L_CARD_GAP = 40, L_BUTTON = 68, L_BUTTON_GAP = 20, L_BUTTON_CY = 103;
    private static final float L_PLATTER_TOP = 814, L_TILE_H = 126;
    private static final int L_INK = 0xFF1D1D1F, L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93;
    private static final String[] TABS = {"Explorar", "Aplicativos", "Séries", "Flamengo"};
    private static final String[] TABS_EN = {"Explore", "Apps", "Shows", "Flamengo"};
    private static final String[] TAB_KIND = {null, null, "tv", null};
    private static final int Z_TOP = 4, Z_CARDS = 5;

    private final Activity activity;
    private final Scene scene = new Scene();
    private final HomeData data;
    private final Recs recs;
    private final Click click;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences ui;
    private final TvInputManager tvInputs;
    private final Layer sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer;
    private int zone = Z_SHELF;

    // Night look (like Apple's "Automatic" appearance): darker from sunset to sunrise, with a 2 s transition.
    private static final long NIGHT_MS = 2000;
    private boolean nightTarget;
    private float night, nightFrom;     // 0 = day, 1 = night
    private long nightAt;
    private final int[] glassNight = new int[Backdrop.GW * Backdrop.GH], frostNight = new int[Backdrop.FW * Backdrop.FH];
    private final int[] neutralGlassPx = new int[Backdrop.GW * Backdrop.GH], neutralFrostPx = new int[Backdrop.FW * Backdrop.FH];
    private boolean nightPair;          // the glass holds both photos (day and night), blended by `night`
    private Shader nightGradient;

    // At the top: tabs (0 to 3) and icon-only buttons (4 to 6); the three cards
    private int tab, topIndex, cardIndex;
    private List<Recs.Pick> cards = new ArrayList<>();
    private final Spring[] topFocus = new Spring[7];
    private final RectF[] topRects = new RectF[7];
    private final Spring[] cardFocus = new Spring[5];
    private final RectF[] cardRects = new RectF[3];
    private final RectF[] flaRowRects = new RectF[5];
    private final List<HomeApps.Item> lightButtons = new ArrayList<>();
    private Paint lTitle, lTab, lTabOn, lCardTitle, lCardMeta, lQuote, lTip;
    private Shader lightGradient;

    // 18 & 19: Cold boot staggered spring entrance & Liquid Tab Morph springs
    private final Spring bootEntrance = new Spring(0.68f, 0.82f, 0);
    private final Spring tabMorph = new Spring(0.52f, 0.78f, 0);
    private final Spring appsMorph = new Spring(0.46f, 0.82f, 0);
    private final Spring pillX = new Spring(0.38f, 0.74f, 0);
    private final Spring pillW = new Spring(0.38f, 0.78f, 0);
    private final Spring flaCol1 = new Spring(0.48f, 0.80f, 1);
    private final Spring flaCol2 = new Spring(0.58f, 0.80f, 1);
    private final Spring flaCol3 = new Spring(0.68f, 0.80f, 1);
    private long lastPauseAt;
    private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader flaSkyBg, flaGlowTop, flaGlowBot, flaCardBg;

    // Tab 1 ("Aplicativos") full-screen grid state
    private List<HomeApps.Item> allApps = new ArrayList<>();
    private Spring[] appTabFocus = new Spring[0];
    private String[] appTabLabels;
    private final Spring appTabScroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedAppTabCell = new RectF();
    private int appTabIndex;

    // Shelf
    private List<HomeApps.Item> shelf = new ArrayList<>();
    private Spring[] tileFocus = new Spring[0];
    private RectF[] tileRects = new RectF[0];
    private final Map<String, Bitmap> crispLogos = new HashMap<>();   // logos already sized for the focused tile
    /** Each app's tile like its Apple TV icon: brand colors (top, bottom) and logo (0 = as is, 1 = white, 2 = dark). */
    private static final Map<String, int[]> TILE_STYLE = new HashMap<>();
    static {
        TILE_STYLE.put("netflix", new int[] {0xFF0A0A0A, 0xFF000000, 0});
        TILE_STYLE.put("youtube", new int[] {0xFFFFFFFF, 0xFFECECEC, 2});
        TILE_STYLE.put("globoplay", new int[] {0xFFFF8A1F, 0xFFFF2D5A, 1});
        TILE_STYLE.put("prime", new int[] {0xFF2AA8FF, 0xFF0A68D4, 1});
        TILE_STYLE.put("disney", new int[] {0xFF173584, 0xFF08143F, 0});
        TILE_STYLE.put("max", new int[] {0xFF5C30E8, 0xFF221684, 0});
        TILE_STYLE.put("appletv", new int[] {0xFF1C1C1E, 0xFF000000, 0});
    }
    private final android.graphics.PorterDuffColorFilter whiteLogo =
            new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
    private final RectF platterRect = new RectF();
    private int shelfIndex;

    // Corner capsule
    private List<HomeApps.Item> statusItems = new ArrayList<>();
    private Spring[] statusFocus = new Spring[0];
    private RectF[] statusRects = new RectF[0];
    private final RectF capsuleRect = new RectF();
    private float capsuleInfoW;
    private int statusIndex;

    // Hero and background
    private List<Recs.Pick> picks = new ArrayList<>();
    private int pickIndex, heroSlide = 1;
    private Hero hero, heroFrom;
    private long heroFadeAt, bgFadeAt;
    private final Spring heroFocus = new Spring(0.28f, 0.85f, 0);
    private final RectF heroButton = new RectF();
    private Backdrop bgFrom, bgTo;     // null = gradient (no photo)
    private final int[] frostMix = new int[Backdrop.FW * Backdrop.FH];

    // Bottom drawer
    private List<HomeApps.Item> drawerItems = new ArrayList<>();
    private int drawerInputs;          // how many leading items are inputs (the rest are shortcuts)
    private Spring[] drawerFocus = new Spring[0];
    private RectF[] drawerRects = new RectF[0];
    private final RectF weatherCard = new RectF(), quotesCard = new RectF();
    private final Spring drawer = new Spring(0.46f, 0.92f, 0);
    private boolean drawerOpen;
    private int drawerIndex;

    // "All Apps" sheet
    private List<HomeApps.Item> others = new ArrayList<>();
    private Spring[] gridFocus = new Spring[0];
    private String[] gridLabels;
    private final Spring sheet = new Spring(0.38f, 0.9f, 0);
    private final Spring scroll = new Spring(0.34f, 1f, 0);
    private final RectF focusedCell = new RectF();
    private boolean sheetOpen;
    private int gridIndex;

    // Toasts
    private final ArrayDeque<String> toasts = new ArrayDeque<>();
    private StaticLayout toast;
    private long toastAt;

    // Frames
    private boolean running, tilesDirty, statusDirty, heroDirty, drawerDirty, gridDirty, motionDirty = true;
    private boolean topBarDirty = true, cardsDirty = true, drawerStaticDirty = true, frameScheduled;
    private boolean drawerWasMoving;
    private long lastNanos, resumedAt, lastStep;
    private int w, h;
    private float k = 1;
    private String clockText = "";
    private long clockMinute = -1;
    private boolean okDown, backDown;

    private Paint labelPaint, metaPaint, clockPaint, buttonPaint, capsuleText, tipPaint, quotePaint, staleQuote;
    private Paint rowTitle, drawerLabel, cardTitle, cardText, cardDim, cardDimRight, bigTemp, hourPaint, hourTemp;
    private Paint pricePaint, changePaint, sheetTitle;
    private Paint flaHeaderTitle, flaHeaderSub, flaTournTitle, flaSectionTitle;
    private Paint flaTeamName, flaMatchMeta, flaBadgeText, flaTableHead, flaTableNum;
    private final Paint flaLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private TextPaint titlePaint, gridLabel, toastPaint;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint shadowBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowSoft = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowDeep = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF tmp = new RectF(), cell = new RectF(), screen = new RectF();
    private final Map<String, Bitmap> shadowCache = new HashMap<>();
    private final Map<String, Bitmap> shelfTiles = new HashMap<>();
    private final Matrix shaderMatrix = new Matrix();
    private final Shader unitSheen = new LinearGradient(0, 0, 0, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final Shader diagSheen = new LinearGradient(0, 0, 0.5f, 1, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
    private final Shader unitRim = new LinearGradient(0, 0, 0, 1, 0xFFFFFFFF, 0x63FFFFFF, Shader.TileMode.CLAMP);
    private final RenderNode topBarNode = new RenderNode("topBar");
    private final RenderNode cardsNode = new RenderNode("cards");
    private final RenderNode drawerStaticNode = new RenderNode("drawerStatic");
    private String[] drawerLabels = new String[0];
    private float[] drawerTextSizes = new float[0];
    private final String[] cardTitles = {"", "", ""}, cardMetas = {"", "", ""};

    /** What the hero shows: a recommendation or, without any, the date, the time and the weather. */
    private static final class Hero {
        String label = "", title = "", meta = "";
        boolean clock, button;
        int dots, dot;
        StaticLayout titleLayout;

        boolean sameText(Hero o) {
            return o != null && clock == o.clock && label.equals(o.label) && title.equals(o.title) && meta.equals(o.meta);
        }
    }

    /** One layer of the screen. It draws in full-screen coordinates: the glass needs each pixel's real position. */
    private final class Layer extends View {
        private final int kind;

        Layer(Context c, int kind) {
            super(c);
            this.kind = kind;
        }

        @Override public boolean hasOverlappingRendering() {
            return false;
        }

        @Override protected void onDraw(Canvas c) {
            if (labelPaint == null) return;
            c.translate(-getLeft(), -getTop());
            // The layer may be shifted (bottom drawer): the glass reads the background from where it really is. The
            // drawer and the top buttons are always drawn as if resting in place (they are only visible there and are
            // not redrawn while they move).
            scene.setOffset(0, kind == DRAWER || kind == STATUS ? 0 : getTranslationY());
            switch (kind) {
                case SKY: drawSky(c); break;
                case HERO: drawLightTop(c); break;
                case STATUS: drawLightButtons(c); break;
                case PLATTER: drawPlatter(c); break;
                case TILES: drawTiles(c); break;
                case DRAWER: drawDrawer(c); break;
                case SHEET: drawSheet(c); break;
                default: drawToast(c); break;
            }
            scene.setOffset(0, 0);
        }
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override public void onInputStateChanged(String inputId, int state) { reload(); }
        @Override public void onInputAdded(String inputId) { reload(); }
        @Override public void onInputRemoved(String inputId) { reload(); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            recs.refreshIfStale();
            checkNotices();
            updatePalette();
            checkNight(true);
            updatePicks();      // three new ones every hour
            scheduleFrame();
            handler.postDelayed(this, 15_000);
        }
    };

    HomeView(Activity a) {
        super(a);
        activity = a;
        data = HomeData.get(a);
        recs = Recs.get(a);
        click = new Click(a);
        ui = a.getSharedPreferences("home-ui", Context.MODE_PRIVATE);
        tvInputs = (TvInputManager) a.getSystemService(Context.TV_INPUT_SERVICE);
        setFocusable(true);
        sky = new Layer(a, SKY);
        heroLayer = new Layer(a, HERO);
        status = new Layer(a, STATUS);
        platter = new Layer(a, PLATTER);
        tiles = new Layer(a, TILES);
        drawerLayer = new Layer(a, DRAWER);
        sheetLayer = new Layer(a, SHEET);
        toastLayer = new Layer(a, TOAST);
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer, sheetLayer, toastLayer}) addView(v);
        drawerLayer.setVisibility(INVISIBLE);
        for (int i = 0; i < 7; i++) {
            topFocus[i] = new Spring(0.26f, 0.85f, 0);
            topRects[i] = new RectF();
        }
        for (int i = 0; i < 5; i++) {
            cardFocus[i] = new Spring(0.32f, 0.78f, 0);
            flaRowRects[i] = new RectF();
            if (i < 3) cardRects[i] = new RectF();
        }
        makeLightButtons();
        recs.setLight(true);
        checkNight(false);
        sheetLayer.setVisibility(INVISIBLE);
        scene.setMotion(0.6f, 0f);
        updatePalette();
        // First time: right away (a cold start opens with the apps already there); missing logos come from reload().
        setShelf(HomeApps.shelf(a, false));
        setAllApps(HomeApps.allTvApps(a));
        setStatusItems(statusList(a));
        setDrawerItems(drawerList(a));
    }

    // MARK: - Lifecycle (called by HomeActivity)

    void resume() {
        if (running) return;
        running = true;
        checkNight(false);
        long nowUp = SystemClock.uptimeMillis();
        boolean coldWake = (resumedAt == 0) || (lastPauseAt > 0 && nowUp - lastPauseAt > 4_000);
        resumedAt = nowUp;
        if (coldWake) {
            bootEntrance.snap(0f);
            bootEntrance.target = 1f;
        } else {
            bootEntrance.snap(1f);
        }
        lastNanos = 0;
        lastStep = 0;
        data.addListener(this);
        recs.addListener(this);
        data.refreshIfStale();
        recs.refreshIfStale();
        if (tvInputs != null) tvInputs.registerCallback(inputCallback, handler);
        handler.postDelayed(tick, 15_000);
        checkNotices();
        reload();
        updatePalette();
        updatePicks();
        if (sheetOpen) closeSheet();
        sheet.snap(sheet.target);
        motionDirty = true;
        invalidateAll();
        scheduleFrame();
    }

    void pause() {
        if (!running) return;
        running = false;
        lastPauseAt = SystemClock.uptimeMillis();
        frameScheduled = false;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        data.removeListener(this);
        recs.removeListener(this);
        if (tvInputs != null) tvInputs.unregisterCallback(inputCallback);
    }

    void destroy() {
        pause();
        click.release();
    }

    private void scheduleFrame() {
        if (!running || frameScheduled) return;
        frameScheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /** Starting state (Home pressed while the home screen is showing): first app focused, drawer and sheet closed. */
    void reset(boolean animated) {
        zone = Z_SHELF;
        shelfIndex = 0;
        sheetOpen = false;
        drawerOpen = false;
        if (animated) {
            sheet.target = 0;
            drawer.target = 0;
            bootEntrance.snap(0.15f);
            bootEntrance.target = 1f;
        } else {
            sheet.snap(0);
            drawer.snap(0);
            bootEntrance.snap(1f);
        }
        if (pickIndex != 0 && !picks.isEmpty()) {
            pickIndex = 0;
            updatePicks();
        }
        if (tab != 0) selectTab(0);
        retarget(!animated);
        motionDirty = true;
        scheduleFrame();
    }

    /** The three corner buttons, with their names in the current language. */
    private void makeLightButtons() {
        lightButtons.clear();
        lightButtons.add(HomeApps.action(HomeApps.LIVE_TV, "live", Ui.tr("TV aberta", "Live TV"), "live_tv"));
        lightButtons.add(HomeApps.action(HomeApps.INPUTS, "inputs", Ui.tr("Entradas HDMI", "HDMI Inputs"), "hdmi"));
        lightButtons.add(HomeApps.action(HomeApps.SETTINGS, "settings", Ui.tr("Configurações", "Settings"), "settings"));
    }

    private static String[] tabs() {
        return Ui.en ? TABS_EN : TABS;
    }

    @Override public void onData() {
        updatePalette();
        checkNight(true);
        layoutStatus();
        heroDirty = statusDirty = drawerDirty = topBarDirty = drawerStaticDirty = true;
        platter.invalidate();
        scheduleFrame();
    }

    @Override public void onRecs() {
        updatePicks();
        scheduleFrame();
    }

    private void invalidateAll() {
        topBarDirty = cardsDirty = drawerStaticDirty = true;
        for (View v : new View[] {sky, heroLayer, status, platter, tiles, drawerLayer}) v.invalidate();
        scheduleFrame();
    }

    // MARK: - Size and layers

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        float kk = width / 1920f;
        measure(sky, width, height);
        measure(heroLayer, width, height);
        measure(status, Math.round(1160 * kk), Math.round(240 * kk));
        // Sized to what they draw (the glass and its shadow; the grown tiles and their shadows): smaller layers are
        // cheaper for the GPU to composite on every frame of the drawer animation.
        measure(platter, width, Math.round(320 * kk));
        measure(tiles, width, Math.round(270 * kk));
        measure(drawerLayer, width, Math.round(600 * kk));
        measure(sheetLayer, width, height);
        measure(toastLayer, Math.round(1000 * kk), Math.round(220 * kk));
        setMeasuredDimension(width, height);
    }

    private static void measure(View v, int width, int height) {
        v.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        float kk = width / 1920f;
        sky.layout(0, 0, width, height);
        place(heroLayer, 0, 0);
        place(status, width - status.getMeasuredWidth(), 0);
        place(platter, 0, Math.round(750 * kk));
        place(tiles, 0, Math.round(790 * kk));
        place(drawerLayer, 0, Math.round(470 * kk));
        sheetLayer.layout(0, 0, width, height);
        sheetLayer.setPivotX(width / 2f);
        sheetLayer.setPivotY(height / 2f);
        place(toastLayer, (width - toastLayer.getMeasuredWidth()) / 2, 0);
    }

    private static void place(View v, int x, int y) {
        v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        screen.set(0, 0, w, h);
        scene.setSize(w, h);
        topBarNode.setPosition(0, 0, w, Math.round(280 * k));
        cardsNode.setPosition(0, 0, w, h);
        drawerStaticNode.setPosition(0, 0, w, h);
        shadowCache.clear();
        shelfTiles.clear();
        makePaints();
        gridLabels = null;
        clockMinute = -1;
        updateClock();
        layoutShelf();
        layoutStatus();
        layoutDrawer();
        layoutLight();
        hero = null;
        heroFrom = null;
        updatePicks();
        motionDirty = topBarDirty = cardsDirty = drawerStaticDirty = true;
        scheduleFrame();
    }

    private void makePaints() {
        labelPaint = Ui.text(Ui.medium, 25 * k, 0xC7FFFFFF);
        labelPaint.setLetterSpacing(0.1f);
        titlePaint = new TextPaint(Ui.text(Ui.displayMedium, 76 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.005f);
        metaPaint = Ui.text(Ui.medium, 29 * k, 0xD1FFFFFF);
        metaPaint.setLetterSpacing(0.014f);
        clockPaint = Ui.numbers(Ui.thin, 176 * k, 0xFFFFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        lTitle = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
        lTitle.setLetterSpacing(0.004f);
        lTab = Ui.text(Ui.medium, 30 * k, L_DIM);
        lTabOn = Ui.text(Ui.semibold, 30 * k, L_INK);
        lCardTitle = Ui.text(Ui.semibold, 31 * k, L_INK);
        lCardTitle.setLetterSpacing(0.012f);
        lCardMeta = Ui.text(Ui.medium, 25 * k, L_DIM);
        lQuote = Ui.numbers(Ui.regular, 21 * k, L_FAINT);
        lTip = Ui.text(Ui.semibold, 25 * k, L_INK);
        lTip.setTextAlign(Paint.Align.CENTER);
        lightGradient = null;
        buttonPaint = Ui.text(Ui.semibold, 29 * k, INK);
        capsuleText = Ui.numbers(Ui.medium, 29 * k, 0xF2FFFFFF);
        tipPaint = Ui.text(Ui.semibold, 25 * k, 0xF2FFFFFF);
        tipPaint.setTextAlign(Paint.Align.CENTER);
        // Quotes: a subtle detail, smaller and dimmer than the rest.
        quotePaint = Ui.numbers(Ui.regular, 21 * k, 0xB8FFFFFF);
        staleQuote = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        staleQuote.setTextAlign(Paint.Align.RIGHT);
        rowTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        drawerLabel = Ui.text(Ui.medium, 23 * k, 0xEBFFFFFF);
        drawerLabel.setFlags(drawerLabel.getFlags() | Paint.LINEAR_TEXT_FLAG);
        drawerLabel.setTextAlign(Paint.Align.CENTER);
        cardTitle = Ui.text(Ui.semibold, 25 * k, 0xB3FFFFFF);
        cardText = Ui.text(Ui.medium, 29 * k, 0xF2FFFFFF);
        cardDim = Ui.numbers(Ui.medium, 23 * k, 0xADFFFFFF);
        cardDimRight = Ui.text(Ui.medium, 21 * k, 0x99FFFFFF);
        cardDimRight.setTextAlign(Paint.Align.RIGHT);
        bigTemp = Ui.numbers(Ui.light, 100 * k, 0xFFFFFFFF);
        hourPaint = Ui.numbers(Ui.medium, 22 * k, 0xADFFFFFF);
        hourPaint.setTextAlign(Paint.Align.CENTER);
        hourTemp = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        hourTemp.setTextAlign(Paint.Align.CENTER);
        pricePaint = Ui.numbers(Ui.medium, 26 * k, 0xF2FFFFFF);
        pricePaint.setTextAlign(Paint.Align.RIGHT);
        changePaint = Ui.numbers(Ui.semibold, 21 * k, 0xFFFFFFFF);
        changePaint.setTextAlign(Paint.Align.CENTER);
        sheetTitle = Ui.text(Ui.displaySemibold, 40 * k, 0xFFFFFFFF);
        sheetTitle.setTextAlign(Paint.Align.CENTER);
        flaHeaderTitle = Ui.text(Ui.displaySemibold, 74 * k, 0xFFFFFFFF);
        flaHeaderSub = Ui.text(Ui.regular, 28 * k, 0xFF8FA0B5);
        flaTournTitle = Ui.text(Ui.regular, 21 * k, 0xFF8FA0B5);
        flaSectionTitle = Ui.text(Ui.semibold, 28 * k, 0xFFFFFFFF);
        flaTeamName = Ui.text(Ui.semibold, 20 * k, 0xFFFFFFFF);
        flaMatchMeta = Ui.text(Ui.regular, 16.5f * k, 0xFF8FA0B5);
        flaBadgeText = Ui.text(Ui.semibold, 19 * k, 0xFFFFFFFF);
        flaBadgeText.setTextAlign(Paint.Align.CENTER);
        flaTableHead = Ui.text(Ui.medium, 17 * k, 0xFF8FA0B5);
        flaTableNum = Ui.numbers(Ui.regular, 19 * k, 0xFFB8C7D9);
        flaLine.setColor(0x24567191);
        flaLine.setStrokeWidth(1.2f * k);
        flaSkyBg = new LinearGradient(0, 0, 0, h, 0xFF070A0F, 0xFF04060A, Shader.TileMode.CLAMP);
        flaGlowTop = new RadialGradient(1680 * k, 110 * k, 820 * k,
                new int[]{0x58A80B18, 0x2460050D, 0x00000000}, null, Shader.TileMode.CLAMP);
        flaGlowBot = new RadialGradient(1120 * k, 1040 * k, 520 * k,
                new int[]{0x389B0814, 0x00000000}, null, Shader.TileMode.CLAMP);
        flaCardBg = new LinearGradient(0, 276 * k, 0, 996 * k, 0xF20C111A, 0xF5080B12, Shader.TileMode.CLAMP);
        gridLabel = new TextPaint(Ui.text(Ui.medium, 24 * k, 0x99FFFFFF));
        gridLabel.setTextAlign(Paint.Align.CENTER);
        toastPaint = new TextPaint(Ui.text(Ui.medium, 26 * k, 0xF2FFFFFF));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2 * k);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.5f * k);
    }

    /** Cached ½-scale blurred shadow bitmap so Skia never allocates per-frame Mask Filtered Masks on the GPU. */
    private void drawShadow(Canvas c, float cx, float cy, float bw, float bh, float r0, float blur, float s, float dy, int alpha) {
        if (alpha <= 1 || bw <= 0 || bh <= 0) return;
        int qw = Math.max(1, Math.round(bw * 0.5f));
        int qh = Math.max(1, Math.round(bh * 0.5f));
        int qr = Math.max(1, Math.round(r0 * 0.5f));
        int qb = Math.max(1, Math.round(blur * 0.5f));
        int pad = qb * 2 + 4;
        String key = qw + "x" + qh + ":" + qr + ":" + qb;
        Bitmap bmp = shadowCache.get(key);
        if (bmp == null) {
            Bitmap raw = Bitmap.createBitmap(qw + 2 * pad, qh + 2 * pad, Bitmap.Config.ARGB_8888);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFF000000);
            sp.setMaskFilter(new BlurMaskFilter(qb, BlurMaskFilter.Blur.NORMAL));
            new Canvas(raw).drawRoundRect(new RectF(pad, pad, pad + qw, pad + qh), qr, qr, sp);
            bmp = Backdrop.gpu(raw);
            shadowCache.put(key, bmp);
        }
        float hw = (bw * 0.5f) * ((qw + 2f * pad) / qw) * s;
        float hh = (bh * 0.5f) * ((qh + 2f * pad) / qh) * s;
        tmp.set(cx - hw, cy + dy - hh, cx + hw, cy + dy + hh);
        shadowBitmapPaint.setAlpha(Math.min(255, alpha));
        c.drawBitmap(bmp, null, tmp, shadowBitmapPaint);
    }

    // MARK: - Frames: 60 per second while something moves; nothing is redrawn when still

    @Override public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (!running) return;
        float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (frameTimeNanos - lastNanos) / 1e9f);
        lastNanos = frameTimeNanos;
        long up = SystemClock.uptimeMillis();

        boolean tilesMoving = step(tileFocus, dt);
        boolean statusMoving = step(statusFocus, dt);
        boolean heroMoving = heroFocus.step(dt);
        boolean topMoving = step(topFocus, dt), cardsMoving = step(cardFocus, dt);
        boolean pillMoving = pillX.step(dt) | pillW.step(dt);
        boolean morphMoving = tabMorph.step(dt) | appsMorph.step(dt);
        boolean appTabMoving = step(appTabFocus, dt) | appTabScroll.step(dt);
        boolean flaColsMoving = flaCol1.step(dt) | flaCol2.step(dt) | flaCol3.step(dt);
        boolean bootMoving = bootEntrance.step(dt);
        if (topMoving || pillMoving || morphMoving) {
            topBarDirty = true;
            status.invalidate();
        }
        if (cardsMoving || flaColsMoving || bootMoving || appTabMoving || morphMoving) cardsDirty = true;
        heroMoving |= topMoving | cardsMoving | pillMoving | morphMoving | flaColsMoving | bootMoving | appTabMoving;
        boolean drawerItemsMoving = step(drawerFocus, dt);
        boolean gridMoving = step(gridFocus, dt) | scroll.step(dt);
        boolean drawerMoving = drawer.step(dt);
        boolean sheetMoving = sheet.step(dt);
        boolean toastFading = tickToast(up);
        boolean heroFading = tickHeroFade(up);
        boolean bgFading = tickBackgroundFade(up);
        boolean nightMoving = tickNight(up);

        boolean springsActive = tilesMoving || statusMoving || heroMoving || drawerItemsMoving || gridMoving || drawerMoving
                || sheetMoving || toastFading || heroFading || bgFading || nightMoving;
        float tSec = up / 1000f;
        boolean shootingStarActive = (Math.max(night, tabMorph.value) > 0.1f) && ((tSec % 14f) < 1.15f);
        int wxCode = data.weather.code;
        boolean rainActive = (wxCode >= 51 && wxCode <= 67) || (wxCode >= 80 && wxCode <= 99);
        boolean skyFxActive = !sheetOpen && !drawerOpen && (springsActive || shootingStarActive || rainActive);

        if (drawerMoving || sheetMoving || morphMoving || bootMoving || motionDirty) updateMotion();
        if (tilesMoving || tilesDirty) tiles.invalidate();
        if (statusMoving || statusDirty || bgFading) status.invalidate();
        if (heroMoving || heroDirty || heroFading) heroLayer.invalidate();
        if (drawerItemsMoving || drawerDirty || bgFading) drawerLayer.invalidate();
        if (gridMoving || gridDirty) sheetLayer.invalidate();
        if (drawerMoving || bgFading || morphMoving || skyFxActive) sky.invalidate();
        if (bgFading || morphMoving || (drawerWasMoving && !drawerMoving)) platter.invalidate();
        drawerWasMoving = drawerMoving;
        tilesDirty = statusDirty = heroDirty = drawerDirty = gridDirty = motionDirty = false;
        if (updateClock()) {
            layoutStatus();
            status.invalidate();
            if (cards.isEmpty() || (hero != null && hero.clock)) {
                refreshHero(false);
                cardsDirty = true;
                heroLayer.invalidate();
            }
        }

        if (springsActive || skyFxActive) {
            scheduleFrame();
        } else {
            lastNanos = 0;
        }
    }

    private static boolean step(Spring[] springs, float dt) {
        boolean moving = false;
        for (Spring s : springs) moving |= s.step(dt);
        return moving;
    }

    /** Drawer and sheet: the layers' positions and opacity (without redrawing their content). */
    private void updateMotion() {
        float d = drawer.value, dc = Ui.clamp(d), s = Ui.clamp(sheet.value);
        float boot = Ui.clamp(bootEntrance.value);
        float hideShelf = Math.max(Ui.clamp(tabMorph.value), Ui.clamp(appsMorph.value));
        float home = (1 - s) * boot, top = home * (1 - smoothstep(0, 0.32f, dc));
        float shift = SHIFT * k;
        float bootLift = (1f - bootEntrance.value) * 44f * k;
        float lift = -d * shift + bootLift;
        show(heroLayer, top, lift);
        show(status, top, lift * 0.6f);
        float shelfVis = home * (1f - hideShelf);
        float shelfLift = lift + hideShelf * 80f * k + (1f - bootEntrance.value) * 36f * k;
        show(platter, shelfVis, shelfLift);
        show(tiles, shelfVis, shelfLift);
        float da = home * smoothstep(0.1f, 0.6f, dc);
        drawerLayer.setTranslationY((1 - d) * shift);
        drawerLayer.setAlpha(da);
        drawerLayer.setVisibility(da > 0.002f ? VISIBLE : INVISIBLE);
        sheetLayer.setVisibility(s > 0.001f || sheetOpen ? VISIBLE : INVISIBLE);
        sheetLayer.setAlpha(s);
        float scale = 1.04f - 0.04f * s;
        sheetLayer.setScaleX(scale);
        sheetLayer.setScaleY(scale);
    }

    private static void show(View v, float alpha, float translationY) {
        v.setAlpha(alpha);
        v.setTranslationY(translationY);
        v.setVisibility(alpha > 0.002f ? VISIBLE : INVISIBLE);
    }

    private boolean updateClock() {
        long now = System.currentTimeMillis();
        long minute = now / 60_000;
        if (minute == clockMinute || clockPaint == null) return false;
        clockMinute = minute;
        clockText = Ui.clock(now);
        return true;
    }

    private void updatePalette() {
        HomeData.Weather wx = data.weather;
        Shaders.sky(scene.palette, System.currentTimeMillis(), wx.sunrise, wx.sunset);
        scene.applyPalette();
    }

    // MARK: - Recommendations: hero and background

    private Recs.Pick currentPick() {
        return picks.isEmpty() ? null : picks.get(Math.min(pickIndex, picks.size() - 1));
    }

    /** This hour's picks: the cards and the background image (called when Recs notifies and every 15 s). */
    private void updatePicks() {
        if (labelPaint == null) return;
        updateCards();
    }

    private static boolean samePicks(List<Recs.Pick> a, List<Recs.Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!Recs.key(a.get(i)).equals(Recs.key(b.get(i)))) return false;
        return true;
    }

    /**
     * Rebuilds the hero. With `animate`, a different title slides in while the previous one slides out; details that
     * arrive later (genre, runtime) just appear.
     */
    private void refreshHero(boolean animate) {
        Hero next = makeHero();
        if (next.sameText(hero) && hero.dots == next.dots && hero.dot == next.dot && hero.button == next.button) return;
        boolean other = hero != null && (hero.clock != next.clock || !hero.title.equals(next.title));
        if (animate && other) {
            heroFrom = hero;
            heroFadeAt = SystemClock.uptimeMillis();
        }
        boolean layoutChanged = hero == null || hero.clock != next.clock;
        hero = next;
        heroDirty = true;
        if (layoutChanged) layoutStatus();
    }

    private Hero makeHero() {
        Hero h = new Hero();
        Recs.Pick p = currentPick();
        if (p == null) {
            h.clock = true;
            h.label = Ui.date(System.currentTimeMillis()).toUpperCase(Ui.en ? java.util.Locale.US : Ui.PT);
            return h;
        }
        h.label = Recs.periodLabel();
        h.title = p.title == null ? "" : p.title;
        h.meta = p.meta == null ? "" : p.meta;
        h.button = p.pkg != null;
        h.dots = picks.size();
        h.dot = pickIndex;
        h.titleLayout = StaticLayout.Builder.obtain(h.title, 0, h.title.length(), titlePaint, Math.max(1, Math.round(HERO_TITLE_W * k)))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0, 0.94f)
                .setIncludePad(false)
                .build();
        return h;
    }

    /** ←/→ on the hero: the next (or previous) of this hour's picks. */
    private boolean browse(int dir) {
        if (picks.size() < 2) return false;
        int next = pickIndex;
        for (int i = 0; i < picks.size(); i++) {
            next = (next + dir + picks.size()) % picks.size();
            if (zone != Z_HERO || picks.get(next).pkg != null) break;
        }
        if (next == pickIndex) return false;
        pickIndex = next;
        heroSlide = dir;
        refreshHero(true);
        Backdrop b = recs.backdrop(currentPick());
        if (b != null) showBackdrop(b);
        return true;
    }

    /** Swaps the background with a cross-fade (from one image to another, from the gradient to an image, or back). */
    private void showBackdrop(Backdrop b) {
        if (b == bgTo) return;
        long up = SystemClock.uptimeMillis();
        // In the middle of another swap, start from whichever is more visible.
        if (bgFadeAt == 0 || fadeEase(bgFadeAt, up) >= 0.5f) bgFrom = bgTo;
        bgTo = b;
        bgFadeAt = up;
        nightPair = false;
        scheduleFrame();
        if (b == null || glassOf(b) == null) return;
        if (bgFrom == null || glassOf(bgFrom) == null) {
            scene.setFrost(frostOf(b));
            scene.setGlassImage(glassOf(b));
        } else {
            scene.setGlassImages(glassOf(bgFrom), glassOf(b));
        }
    }

    /** What the glass shows: the photo lightened (by day), darkened (at night) or blended in between. */
    private int[] glassOf(Backdrop b) {
        if (b.nightGlass == null || night <= 0.001f) return b.lightGlass;
        if (night >= 0.999f) return b.nightGlass;
        // During the transition, the blend (only for the current background; a photo fading in uses the nearer side).
        if (b != bgTo) return night < 0.5f ? b.lightGlass : b.nightGlass;
        for (int i = 0; i < glassNight.length; i++) glassNight[i] = mix(b.lightGlass[i], b.nightGlass[i], night);
        return glassNight;
    }

    /** No photo: the glass reads the background's own gray (not Scene's default aurora, which isn't on screen). */
    private void neutralGlass() {
        int color = blend(0xFFEBEBF0, 0xFF141416, night);
        java.util.Arrays.fill(neutralFrostPx, color);
        java.util.Arrays.fill(neutralGlassPx, color);
        scene.setFrost(neutralFrostPx);
        scene.setGlassImage(neutralGlassPx);
        nightPair = false;
    }

    /** What the frosted glass shows (drawer, toasts): the blurred photo, darkened at night. */
    private int[] frostOf(Backdrop b) {
        if (b.nightFrost == null || night <= 0.001f) return b.frost;
        if (night >= 0.999f) return b.nightFrost;
        if (b != bgTo) return night < 0.5f ? b.frost : b.nightFrost;
        for (int i = 0; i < frostNight.length; i++) frostNight[i] = mix(b.frost[i], b.nightFrost[i], night);
        return frostNight;
    }

    // MARK: - Night look

    /** Day or night right now (from the sun at your location); `animate`: with the transition. */
    private void checkNight(boolean animate) {
        boolean want = data.isNight(System.currentTimeMillis());
        if (want == nightTarget) return;
        nightTarget = want;
        if (animate && running) {
            nightFrom = night;
            nightAt = SystemClock.uptimeMillis();
            scheduleFrame();
        } else {
            night = want ? 1 : 0;
            nightAt = 0;
            nightChanged();
        }
    }

    /** Advances the day ⇄ night transition; true while it runs. */
    private boolean tickNight(long up) {
        if (nightAt == 0) return false;
        float t = Ui.clamp((up - nightAt) / NIGHT_MS), to = nightTarget ? 1 : 0;
        night = nightFrom + (to - nightFrom) * t * t * (3 - 2 * t);
        if (t >= 1) {
            night = to;
            nightAt = 0;
        }
        nightChanged();
        return true;
    }

    /** Redraws for the current night value (the glass now reads the lightened, blended or darkened photo). */
    private void nightChanged() {
        if (bgFadeAt == 0 && bgTo != null && bgTo.lightGlass != null) {
            scene.setFrost(frostOf(bgTo));
            if (nightAt != 0 && bgTo.nightGlass != null) {
                if (!nightPair) {
                    scene.setGlassImages(bgTo.lightGlass, bgTo.nightGlass);
                    nightPair = true;
                }
                scene.setGlassMix(night);
            } else {
                scene.setGlassImage(glassOf(bgTo));
                nightPair = false;
            }
        } else if (bgFadeAt == 0 && bgTo == null) {
            neutralGlass();
        }
        heroDirty = statusDirty = tilesDirty = drawerDirty = topBarDirty = cardsDirty = drawerStaticDirty = true;
        sky.invalidate();
        platter.invalidate();
        scheduleFrame();
    }

    /** How dark it is: 0 = day, 1 = night (in between during the transition). */
    private float dark() {
        return Math.max(night, Ui.clamp(tabMorph.value));
    }

    /** The day color or the night color, depending on the night value (blended during the transition). */
    private int tone(int onLight, int onDark) {
        return blend(onLight, onDark, dark());
    }

    private float tone(float onLight, float onDark) {
        return onLight + (onDark - onLight) * dark();
    }

    /** Text colors for the current night value (dark by day, white at night). */
    private void applyTone() {
        lTitle.setColor(tone(L_INK, WHITE));
        lTab.setColor(tone(L_DIM, 0xFF8E8E93));
        lTabOn.setColor(tone(L_INK, WHITE));
        lCardTitle.setColor(tone(L_INK, WHITE));
        lCardMeta.setColor(tone(L_DIM, 0xFF98989D));
        lQuote.setColor(tone(L_FAINT, 0xB8FFFFFF));
        lTip.setColor(tone(L_INK, WHITE));
        staleQuote.setColor(tone(L_FAINT, 0x99FFFFFF));
    }

    private boolean tickBackgroundFade(long up) {
        if (bgFadeAt == 0) return false;
        float e = fadeEase(bgFadeAt, up);
        if (bgTo != null && bgFrom != null && glassOf(bgFrom) != null && glassOf(bgTo) != null) {
            int[] from = frostOf(bgFrom), to = frostOf(bgTo);
            for (int i = 0; i < frostMix.length; i++) frostMix[i] = mix(from[i], to[i], e);
            scene.setFrost(frostMix);
            scene.setGlassMix(e);
        }
        if (e >= 1) {
            bgFadeAt = 0;
            bgFrom = null;
            if (bgTo == null) {
                neutralGlass();
            } else {
                scene.setFrost(frostOf(bgTo));
                if (glassOf(bgTo) != null) scene.setGlassImage(glassOf(bgTo));
            }
        }
        return true;
    }

    private boolean tickHeroFade(long up) {
        if (heroFadeAt == 0) return false;
        if (fadeEase(heroFadeAt, up) >= 1) {
            heroFadeAt = 0;
            heroFrom = null;
        }
        return true;
    }

    private float fadeEase(long start, long up) {
        return fadeEase(start, up, FADE_MS);
    }

    private static float fadeEase(long start, long up, float duration) {
        float t = Ui.clamp((up - start) / duration);
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        int r = Math.round(((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = Math.round(((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = Math.round((a & 255) * (1 - f) + (b & 255) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // MARK: - Background

    private void drawSky(Canvas c) {
        drawLightSky(c);
    }

    /** Blends a color on top (the drawer's veil) as part of drawing the image, without another layer. */
    private static android.graphics.ColorFilter veil(float amount, int color) {
        if (amount <= 0.002f) return null;
        float keep = 1 - amount;
        return new android.graphics.ColorMatrixColorFilter(new float[] {
            keep, 0, 0, 0, ((color >> 16) & 255) * amount,
            0, keep, 0, 0, ((color >> 8) & 255) * amount,
            0, 0, keep, 0, (color & 255) * amount,
            0, 0, 0, 1, 0});
    }

    private void drawImage(Canvas c, Bitmap image, float alpha) {
        if (image == null || alpha <= 0.004f) return;
        imagePaint.setAlpha(Math.round(255 * Math.min(1, alpha)));
        c.drawBitmap(image, null, screen, imagePaint);
    }

    // MARK: - Hero

    private static void text(Canvas c, String s, float x, float y, Paint p, int color, float alpha) {
        if (s == null || s.isEmpty()) return;
        p.setColor(Ui.alpha(color, alpha));
        c.drawText(s, x, y, p);
    }

    // MARK: - Corner capsule: time and weather (when the hero shows a recommendation) and icon-only buttons

    /** Live TV, the inputs with a device on, All Apps and Settings. */
    private static List<HomeApps.Item> statusList(Context c) {
        List<HomeApps.Item> list = new ArrayList<>();
        list.addAll(HomeApps.shortcuts(c));
        list.add(HomeApps.settings());
        return list;
    }

    private void setStatusItems(List<HomeApps.Item> list) {
        boolean same = list.size() == statusItems.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(statusItems.get(i).key);
        String focusedKey = statusIndex < statusItems.size() ? statusItems.get(statusIndex).key : null;
        statusItems = list;
        if (!same || statusFocus.length != list.size()) {
            statusFocus = new Spring[list.size()];
            statusRects = new RectF[list.size()];
            int index = Math.min(statusIndex, Math.max(0, list.size() - 1));
            for (int i = 0; i < statusFocus.length; i++) {
                statusFocus[i] = new Spring(0.26f, 0.85f, 0);
                statusRects[i] = new RectF();
                if (list.get(i).key.equals(focusedKey)) index = i;   // a device came on: keep focus on the same button
            }
            statusIndex = index;
            if (zone == Z_STATUS && list.isEmpty()) zone = Z_SHELF;
            aim(statusFocus, zone == Z_STATUS ? statusIndex : -1, true);   // only the capsule's springs
            statusDirty = true;
        }
        layoutStatus();
    }

    private boolean showInfo() {
        return hero != null && !hero.clock;
    }

    private void layoutStatus() {
        if (capsuleText == null) return;
        int n = statusItems.size();
        float b = BUTTON * k, gap = 4 * k, pad = 6 * k;
        float info = 0;
        if (showInfo()) {
            info = 26 * k + capsuleText.measureText(clockText);
            HomeData.Weather wx = data.weather;
            if (!Double.isNaN(wx.temp)) {
                info += 22 * k + Symbols.width(getContext(), Symbols.weather(wx.code, wx.isDay), 24 * k) + 8 * k
                        + capsuleText.measureText(HomeData.degrees(wx.temp));
            }
            info += 22 * k;
        }
        capsuleInfoW = info;
        float buttons = n * b + Math.max(0, n - 1) * gap;
        float right = CONTENT_R * k, top = CAPSULE_TOP * k;
        float left = right - pad - buttons - (info > 0 ? info + 12 * k : pad);
        capsuleRect.set(left, top, right, top + CAPSULE_H * k);
        float x = right - pad - buttons, cy = capsuleRect.centerY();
        for (int i = 0; i < n; i++) {
            statusRects[i].set(x, cy - b / 2, x + b, cy + b / 2);
            x += b + gap;
        }
        statusDirty = true;
    }

    // MARK: - Quotes and shelf

    private void setShelf(List<HomeApps.Item> list) {
        boolean same = list.size() == shelf.size();
        for (int i = 0; same && i < list.size(); i++) same = list.get(i).key.equals(shelf.get(i).key);
        shelf = list;
        if (!same || tileFocus.length != list.size()) {
            tileFocus = new Spring[list.size()];
            tileRects = new RectF[list.size()];
            for (int i = 0; i < tileFocus.length; i++) {
                tileFocus[i] = new Spring(0.34f, 0.74f, 0);
                tileRects[i] = new RectF();
            }
            shelfIndex = Math.min(shelfIndex, Math.max(0, list.size() - 1));
            retarget(true);
            layoutShelf();
        }
        tilesDirty = true;
        platter.invalidate();
    }

    private void layoutShelf() {
        int n = shelf.size();
        float pad = SHELF_PAD * k, gap = TILE_GAP * k, th = L_TILE_H * k;
        float full = (CONTENT_R - MARGIN) * k;
        float tw = Math.min(TILE_MAX_W * k, (full - Math.max(0, n - 1) * gap) / Math.max(1, n));
        float width = n * tw + Math.max(0, n - 1) * gap, left = (w - width) / 2f;
        float top = L_PLATTER_TOP * k;
        platterRect.set(left - pad, top, left + width + pad, top + th + 2 * pad);
        float x = left, y = top + pad;
        for (int i = 0; i < n; i++) {
            tileRects[i].set(x, y, x + tw, y + th);
            x += tw + gap;
        }
        crispLogos.clear();
        shelfTiles.clear();
    }

    /** Shelf layer: the glass bar and its shadow (it only changes with the background). */
    private void drawPlatter(Canvas c) {
        if (shelf.isEmpty() || tabMorph.value > 0.99f || appsMorph.value > 0.99f) return;
        float radius = (TILE_RADIUS + SHELF_PAD) * k;
        drawShadow(c, platterRect.centerX(), platterRect.centerY(), platterRect.width(), platterRect.height(),
                radius, 26 * k, 1f, 18 * k, Math.round(255 * tone(0.12f, 0.32f)));
        // White glass by day; smoked glass at night.
        scene.liquid(c, platterRect, radius, tone(0xFFFFFFFF, SMOKE), tone(0.42f, 0.12f), tone(0.03f, 0.08f),
                tone(30, 34) * k, tone(22, 28) * k, tone(0.65f, 0.95f), 1);
    }

    /** Coin logo + price in the configured currency, in a row at the right end of the tab bar. */
    private void drawQuotes(Canvas c) {
        float right = platterRect.isEmpty() ? CONTENT_R * k : platterRect.right - SHELF_PAD * k;
        applyTone();
        Paint text = lQuote;
        // The corner buttons' names appear in this row: the quotes fade out while one of them has focus.
        float fade = 1;
        for (int i = 4; i < 7; i++) fade = Math.min(fade, 1 - Ui.clamp(topFocus[i].value));
        if (fade <= 0.01f) return;
        int textColor = text.getColor();
        text.setColor(Ui.alpha(textColor, fade));
        // On the tab row, at the right.
        float morph = Ui.clamp(tabMorph.value);
        float baseY = (L_TABBAR_TOP + L_TABBAR_H / 2) + (174 - (L_TABBAR_TOP + L_TABBAR_H / 2)) * morph;
        float base = baseY * k + Ui.halfCap(text);
        float cap = Ui.cap(text), gap = 6 * k, spacing = 24 * k;
        int n = HomeData.coinCount();
        String[] values = new String[n];
        float[] glyphs = new float[n];
        float total = spacing * (n - 1);
        for (int i = 0; i < n; i++) {
            values[i] = HomeData.compact(data.prices[i]);
            glyphs[i] = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            total += glyphs[i] + gap + text.measureText(values[i]);
        }
        float x = right - total;
        for (int i = 0; i < n; i++) {
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + glyphs[i] / 2, base - cap / 2, cap,
                    Ui.alpha(tone(0xB38E8E93, 0x8CFFFFFF), fade));
            x += glyphs[i] + gap;
            c.drawText(values[i], x, base, text);
            x += text.measureText(values[i]) + spacing;
        }
        text.setColor(textColor);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        if (!stale.isEmpty()) c.drawText(stale, right - total - 24 * k, base, staleQuote);
    }

    private void drawTiles(Canvas c) {
        if (tabMorph.value > 0.99f || appsMorph.value > 0.99f) return;
        // The focused one last: it grows over its neighbors without being covered by them.
        int top = -1;
        float most = 0.02f;
        for (int i = 0; i < shelf.size(); i++) {
            if (tileFocus[i].value > most) {
                most = tileFocus[i].value;
                top = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < shelf.size(); i++) {
                if ((pass == 0) == (i == top)) continue;
                drawShelfTile(c, i);
            }
        }
    }

    /**
     * App icon like the ones in the tvOS 27 Dock: the brand color edge to edge (a soft gradient) with the logo.
     * Focused: it grows 20% from the center, with a deep shadow (it "rises" toward the viewer) and a subtle glow at the
     * top. No border and no reflection, as Apple does it.
     */
    private void drawShelfTile(Canvas c, int i) {
        HomeApps.Item it = shelf.get(i);
        float f = tileFocus[i].value, fc = Ui.clamp(f), s = 1 + FOCUS_GROW * f;
        RectF base = tileRects[i];
        float bw = base.width(), bh = base.height(), r0 = TILE_RADIUS * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        drawShadow(c, cx, cy, bw, bh, r0, 30 * k, s, (4 + 14 * f) * k,
                Math.round(255 * (tone(0.14f, 0.3f) + tone(0.18f, 0.3f) * fc)));
        Bitmap tileBmp = shelfTileBitmap(it, bw, bh, r0);
        if (tileBmp != null) {
            c.drawBitmap(tileBmp, null, cell, bitmapPaint);
            if (fc > 0.01f && it.logo != null && TILE_STYLE.containsKey(it.key)) {
                shaderMatrix.setScale(1, cell.height() * 0.5f);
                shaderMatrix.postTranslate(0, cell.top);
                unitSheen.setLocalMatrix(shaderMatrix);
                tileFill.setShader(unitSheen);
                tileFill.setAlpha(Math.round(255 * 0.08f * fc));
                c.drawRoundRect(cell, r, r, tileFill);
                tileFill.setShader(null);
                tileFill.setAlpha(255);
            }
        }
    }

    private Bitmap shelfTileBitmap(HomeApps.Item it, float bw, float bh, float r0) {
        int fw = Math.max(1, Math.round(bw * (1 + FOCUS_GROW)));
        int fh = Math.max(1, Math.round(bh * (1 + FOCUS_GROW)));
        String key = it.key + "@" + System.identityHashCode(it.logo != null ? it.logo : it.tile) + "@" + fw + "x" + fh;
        Bitmap cached = shelfTiles.get(key);
        if (cached != null) return cached;
        float fr = r0 * (1 + FOCUS_GROW);
        Bitmap raw = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        RectF box = new RectF(0, 0, fw, fh);
        int[] style = TILE_STYLE.get(it.key);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (it.logo != null && style != null) {
            p.setShader(new LinearGradient(0, 0, 0, fh, style[0], style[1], Shader.TileMode.CLAMP));
            rc.drawRoundRect(box, fr, fr, p);
            p.setShader(null);
            drawLogo(rc, it, box, 1 + FOCUS_GROW, style[2]);
        } else if (it.logo != null) {
            p.setColor(0xFF161618);
            rc.drawRoundRect(box, fr, fr, p);
            drawLogo(rc, it, box, 1 + FOCUS_GROW, 0);
        } else if (it.tile != null) {
            p.setShader(new android.graphics.BitmapShader(
                    Bitmap.createScaledBitmap(it.tile, fw, fh, true), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            rc.drawRoundRect(box, fr, fr, p);
        }
        cached = Backdrop.gpu(raw);
        shelfTiles.put(key, cached);
        return cached;
    }

    /**
     * Logo at its "optical" size (all with the same amount of ink, plus each one's own adjustment, never past 80% × 62%
     * of the tile), centered and already filtered down to the focused tile size: when focused it is drawn pixel for
     * pixel, without resampling.
     */
    private void drawLogo(Canvas c, HomeApps.Item it, RectF box, float s, int mode) {
        Bitmap logo = crispLogo(it, mode == 2 && it.logoDark != null);
        if (logo == null) return;
        float scale = s / (1 + FOCUS_GROW);
        float dw = logo.getWidth() * scale, dh = logo.getHeight() * scale;
        float left = box.centerX() - dw / 2, top = box.centerY() - dh / 2;
        if (scale > 0.998f) {
            left = Math.round(left);
            top = Math.round(top);
        }
        tmp.set(left, top, left + dw, top + dh);
        bitmapPaint.setColorFilter(mode == 1 ? whiteLogo : null);
        c.drawBitmap(logo, null, tmp, bitmapPaint);
        bitmapPaint.setColorFilter(null);
    }

    private Bitmap crispLogo(HomeApps.Item it, boolean dark) {
        Bitmap src = dark ? it.logoDark : it.logo;
        if (tileRects.length == 0) return src;
        String key = it.key + "@" + System.identityHashCode(src) + "@" + Math.round(tileRects[0].width());
        Bitmap cached = crispLogos.get(key);
        if (cached != null) return cached;
        float bw = tileRects[0].width() * (1 + FOCUS_GROW), bh = tileRects[0].height() * (1 + FOCUS_GROW);
        float lw = src.getWidth(), lh = src.getHeight();
        float scale = (float) Math.sqrt(bw * bh * 0.085f / (lw * lh * Math.max(0.08f, it.logoInk))) * it.logoScale;
        scale = Math.min(scale, Math.min(bw * 0.76f / lw, bh * 0.58f / lh));
        Bitmap out = downscale(src, Math.round(lw * scale), Math.round(lh * scale));
        crispLogos.put(key, out);
        return out;
    }

    /** Halves the size while it can (a 2×2 average, no aliasing), then finishes at the exact size. */
    private static Bitmap downscale(Bitmap src, int w, int h) {
        w = Math.max(1, w);
        h = Math.max(1, h);
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        Bitmap out = Bitmap.createScaledBitmap(b, w, h, true);
        if (b != src && b != out) b.recycle();
        return out;
    }

    // MARK: - Bottom drawer

    private static List<HomeApps.Item> drawerList(Context c) {
        List<HomeApps.Item> list = HomeApps.allInputs(c);
        list.addAll(HomeApps.drawerShortcuts());
        return list;
    }

    private void setDrawerItems(List<HomeApps.Item> list) {
        int inputs = 0;
        while (inputs < list.size() && (list.get(inputs).kind == HomeApps.INPUT || list.get(inputs).kind == HomeApps.LIVE_TV)) inputs++;
        boolean same = list.size() == drawerItems.size();
        for (int i = 0; same && i < list.size(); i++) {
            same = list.get(i).key.equals(drawerItems.get(i).key) && list.get(i).connected == drawerItems.get(i).connected;
        }
        drawerItems = list;
        drawerInputs = inputs;
        if (drawerFocus.length != list.size()) {
            drawerFocus = new Spring[list.size()];
            drawerRects = new RectF[list.size()];
            for (int i = 0; i < drawerFocus.length; i++) {
                drawerFocus[i] = new Spring(0.28f, 0.85f, 0);
                drawerRects[i] = new RectF();
            }
            drawerIndex = Math.min(drawerIndex, Math.max(0, list.size() - 1));
            retarget(true);
        }
        layoutDrawer();
        if (!same) drawerDirty = true;
    }

    private void layoutDrawer() {
        int n = drawerItems.size();
        float left = MARGIN * k, right = CONTENT_R * k;
        float gap = 18 * k, groupGap = 56 * k;
        int groups = drawerInputs > 0 && drawerInputs < n ? 2 : 1;
        float tw = Math.min(230 * k, (right - left - Math.max(0, n - groups) * gap - (groups - 1) * groupGap) / Math.max(1, n));
        float x = left, top = ROW_TOP * k;
        drawerLabels = new String[n];
        drawerTextSizes = new float[n];
        for (int i = 0; i < n; i++) {
            if (i == drawerInputs && i > 0) x += groupGap - gap;
            drawerRects[i].set(x, top, x + tw, top + ROW_H * k);
            x += tw + gap;
            if (drawerLabel != null) {
                String raw = drawerItems.get(i).label;
                float size = 23 * k, room = tw - 16 * k;
                drawerLabel.setTextSize(size);
                float wide = drawerLabel.measureText(raw);
                if (wide > room && wide > 0) size = Math.max(18 * k, size * room / wide);
                drawerLabel.setTextSize(size);
                drawerTextSizes[i] = size;
                drawerLabels[i] = TextUtils.ellipsize(raw, new TextPaint(drawerLabel), room, TextUtils.TruncateAt.END).toString();
            } else {
                drawerTextSizes[i] = 23 * k;
                drawerLabels[i] = drawerItems.get(i).label;
            }
        }
        if (drawerLabel != null) drawerLabel.setTextSize(23 * k);
        float cardsTop = CARDS_TOP * k, split = left + (right - left) * 0.44f;
        weatherCard.set(left, cardsTop, split - 16 * k, cardsTop + CARDS_H * k);
        quotesCard.set(split + 16 * k, cardsTop, right, cardsTop + CARDS_H * k);
        drawerDirty = drawerStaticDirty = true;
    }

    private void openDrawer() {
        if (drawerItems.isEmpty()) return;
        drawerOpen = true;
        drawer.target = 1;
        zone = Z_DRAWER;
        drawerIndex = shelf.isEmpty() ? 0 : nearest(drawerRects, tileRects[shelfIndex].centerX());
        for (int i = 0; i < drawerFocus.length; i++) drawerFocus[i].snap(i == drawerIndex ? 1 : 0);
        drawerDirty = true;
        scheduleFrame();
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.target = 0;
        zone = Z_SHELF;
        for (Spring f : drawerFocus) f.snap(0);   // the drawer goes away: its focus doesn't need to animate
        if (!shelf.isEmpty() && drawerIndex < drawerRects.length) shelfIndex = nearest(tileRects, drawerRects[drawerIndex].centerX());
        scheduleFrame();
    }

    /** Color for the drawer's text and symbols: dark by day, white at night. */
    private int drawerInk(float alpha) {
        return Ui.alpha(tone(L_INK, 0xFFFFFFFF), alpha);
    }

    /** Glass for a drawer card or button: white by day, smoked at night (both during the transition). */
    private void drawerGlass(Canvas c, RectF r, float radius, float lightTint, int shadowColor, float drop) {
        float dk = dark();
        if (dk < 0.999f) {
            drawShadow(c, r.centerX(), r.centerY(), r.width(), r.height(), radius, 26 * k, 1f, drop,
                    Math.round(((shadowColor >>> 24) & 255) * (1 - dk)));
            scene.glass(c, r, radius, 0xFFFFFFFF, lightTint, 0, 0, 0.02f, 1, 1 - dk);
        }
        if (dk > 0.001f) scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
    }

    private void drawerCard(Canvas c, RectF r) {
        drawerGlass(c, r, 30 * k, 0.72f, 0x1A000000, 10 * k);
    }

    private void drawDrawer(Canvas c) {
        if (drawerItems.isEmpty()) return;
        if (drawerStaticDirty || !drawerStaticNode.hasDisplayList()) {
            RecordingCanvas rc = drawerStaticNode.beginRecording(w, h);
            try {
                rowTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
                cardTitle.setColor(tone(L_DIM, 0xB3FFFFFF));
                cardText.setColor(drawerInk(0.95f));
                cardDim.setColor(tone(L_DIM, 0xADFFFFFF));
                cardDimRight.setColor(tone(L_FAINT, 0x99FFFFFF));
                bigTemp.setColor(drawerInk(1));
                hourPaint.setColor(tone(L_DIM, 0xADFFFFFF));
                hourTemp.setColor(drawerInk(0.95f));
                pricePaint.setColor(drawerInk(0.95f));
                float titleBase = ROW_TITLE * k;
                if (drawerInputs > 0) rc.drawText(Ui.tr("Entradas", "Inputs"), drawerRects[0].left + 4 * k, titleBase, rowTitle);
                if (drawerInputs < drawerItems.size()) rc.drawText(Ui.tr("Atalhos", "Shortcuts"), drawerRects[drawerInputs].left + 4 * k, titleBase, rowTitle);
                drawWeatherCard(rc, weatherCard);
                drawQuotesCard(rc, quotesCard);
            } finally {
                drawerStaticNode.endRecording();
            }
            drawerStaticDirty = false;
        }
        c.drawRenderNode(drawerStaticNode);
        int top = -1;
        for (int i = 0; i < drawerItems.size(); i++) if (drawerFocus[i].value > 0.02f && (top < 0 || drawerFocus[i].value > drawerFocus[top].value)) top = i;
        for (int i = 0; i < drawerItems.size(); i++) if (i != top) drawDrawerTile(c, i);
        if (top >= 0) drawDrawerTile(c, top);
    }

    /** Square drawer button: symbol and name; an input with no device is dimmed. Focused: white, with dark text. */
    private void drawDrawerTile(Canvas c, int i) {
        HomeApps.Item it = drawerItems.get(i);
        float f = Ui.clamp(drawerFocus[i].value), s = 1 + 0.07f * f;
        RectF base = drawerRects[i];
        float bw = base.width(), bh = base.height(), r0 = 20 * k;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        if (f > 0.01f) {
            drawShadow(c, cx, cy, bw, bh, r0, 26 * k, s, 12 * k * f, Math.round(255 * 0.45f * f));
        }
        if (f < 0.99f) {
            float dk = dark();
            if (dk < 0.999f) {
                drawShadow(c, cx, cy, bw, bh, r0, 26 * k, s, 6 * k, Math.round(0x14 * (1 - dk)));
                scene.glass(c, cell, r, 0xFFFFFFFF, 0.7f, 0, 0, 0.02f, 1, 1 - dk);
            }
            if (dk > 0.001f) scene.glass(c, cell, r, SMOKE, 0.4f, 0, 0, 0.02f, 1, dk);
        }
        if (f > 0.01f) {
            fill.setShader(null);
            fill.setColor(Ui.alpha(WHITE, f));
            c.drawRoundRect(cell, r, r, fill);
        }
        boolean off = it.kind == HomeApps.INPUT && !it.connected;
        int ink = blend(tone(off ? 0xFF8E8E93 : L_INK, off ? 0x6BFFFFFF : 0xF2FFFFFF), INK, f);
        c.save();
        c.translate(cx, cy);
        c.scale(s, s);
        Symbols.draw(getContext(), c, it.symbol, 0, -bh * 0.1f, 34 * k, ink);
        drawerLabel.setColor(ink);
        float size = i < drawerTextSizes.length ? drawerTextSizes[i] : 23 * k;
        String label = i < drawerLabels.length ? drawerLabels[i] : it.label;
        drawerLabel.setTextSize(size);
        c.drawText(label, 0, bh * 0.3f, drawerLabel);
        c.restore();
    }

    /** Weather for your location: now (large), the next 6 hours, and sunrise and sunset. */
    private void drawWeatherCard(Canvas c, RectF r) {
        drawerCard(c, r);
        float x = r.left + 34 * k;
        c.drawText(Config.CITY.trim().isEmpty() ? Ui.tr("Clima", "Weather") : Config.CITY.trim(), x, r.top + 54 * k, cardTitle);
        HomeData.Weather wx = data.weather;
        if (Double.isNaN(wx.temp)) {
            c.drawText(Ui.tr("Sem dados do clima agora.", "No weather data right now."), x, r.top + 120 * k, cardDim);
            return;
        }
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, 52 * k);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, r.top + 132 * k, 52 * k, drawerInk(0.95f));
        String now = HomeData.degrees(wx.temp);
        c.drawText(now, x + sw + 18 * k - Ui.leftBearing(bigTemp, now), r.top + 170 * k, bigTemp);
        c.drawText(HomeData.condition(wx.code, wx.isDay), x, r.top + 220 * k, cardText);
        c.drawText(Ui.en ? "H:" + HomeData.degrees(wx.max) + "   L:" + HomeData.degrees(wx.min)
                : "Máx. " + HomeData.degrees(wx.max) + "   Mín. " + HomeData.degrees(wx.min), x, r.top + 258 * k, cardDim);
        float split = r.left + 330 * k;
        fill.setShader(null);
        fill.setColor(tone(0x1A000000, 0x24FFFFFF));
        c.drawRoundRect(split - k, r.top + 34 * k, split + k, r.bottom - 34 * k, k, k, fill);
        float hx = split + 24 * k, hw = (r.right - 24 * k - hx) / 5;
        for (int i = 0; i < wx.hours.size() && i < 5; i++) {
            float hc = hx + hw * (i + 0.5f);
            c.drawText(HomeData.hour(wx.hours.get(i)), hc, r.top + 96 * k, hourPaint);
            Symbols.draw(getContext(), c, Symbols.weather(wx.hourCode.get(i), wx.hourDay.get(i)), hc, r.top + 142 * k, 30 * k, drawerInk(0.9f));
            c.drawText(HomeData.degrees(wx.hourTemp.get(i)), hc, r.top + 196 * k, hourTemp);
        }
        float sx = hx, base = r.top + 258 * k;
        if (wx.sunrise != 0) sx = sunItem(c, "sunrise", Ui.tr("Nascer ", "Sunrise ") + Ui.time(wx.sunrise), sx, base) + 36 * k;
        if (wx.sunset != 0) sunItem(c, "sunset", Ui.tr("Pôr do sol ", "Sunset ") + Ui.time(wx.sunset), sx, base);
    }

    private float sunItem(Canvas c, String symbol, String label, float x, float base) {
        float size = 22 * k, sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, x + sw / 2, base - Ui.halfCap(cardDim), size, tone(L_DIM, 0xB3FFFFFF));
        c.drawText(label, x + sw + 10 * k, base, cardDim);
        return x + sw + 10 * k + cardDim.measureText(label);
    }

    /** Prices in the configured currency with the 24 h change (green/red, like Apple's Stocks app). */
    private void drawQuotesCard(Canvas c, RectF r) {
        drawerCard(c, r);
        float x = r.left + 34 * k, right = r.right - 34 * k;
        c.drawText(Ui.tr("Cotações", "Crypto"), x, r.top + 54 * k, cardTitle);
        String stale = HomeData.staleness(data.pricesAt, 5 * 60_000L);
        c.drawText(stale.isEmpty() ? Ui.tr("variação em 24 h", "24h change") : Ui.tr("de ", "") + stale, right, r.top + 54 * k, cardDimRight);
        float cap = Ui.cap(cardText);
        for (int i = 0; i < HomeData.coinCount(); i++) {
            float base = r.top + (110 + 52 * i) * k, mid = base - cap / 2;
            float gw = Glyphs.width(getContext(), HomeData.COINS[i], cap);
            Glyphs.coin(getContext(), c, HomeData.COINS[i], x + 14 * k, mid, cap, tone(L_DIM, 0xCCFFFFFF));
            c.drawText(HomeData.COIN_NAMES[i], x + Math.max(gw, 28 * k) / 2 + 30 * k, base, cardText);
            c.drawText(HomeData.brl(data.prices[i]), right - 128 * k, base, pricePaint);
            double change = data.changes[i];
            if (Double.isNaN(change)) continue;
            int color = change >= 0 ? tone(0xFF248A3D, UP_COLOR) : tone(0xFFD70015, DOWN_COLOR);
            tmp.set(right - 108 * k, mid - 17 * k, right, mid + 17 * k);
            fill.setShader(null);
            fill.setColor(Ui.alpha(color, 0.2f));
            c.drawRoundRect(tmp, 17 * k, 17 * k, fill);
            changePaint.setColor(color);
            c.drawText(HomeData.change(change), tmp.centerX(), mid + Ui.halfCap(changePaint), changePaint);
        }
    }

    // MARK: - Focus

    /** Sets the springs' targets from the current focus (snap: no animation). */
    private void retarget(boolean snap) {
        // Only the group whose focus changed is redrawn (moving along the shelf doesn't redraw the hero or capsule).
        boolean tilesChanged = aim(tileFocus, !sheetOpen && zone == Z_SHELF ? shelfIndex : -1, snap);
        boolean statusChanged = aim(statusFocus, !sheetOpen && zone == Z_STATUS ? statusIndex : -1, snap);
        boolean drawerChanged = aim(drawerFocus, !sheetOpen && zone == Z_DRAWER ? drawerIndex : -1, snap);
        boolean heroChanged = heroFocus.target != (!sheetOpen && zone == Z_HERO ? 1 : 0);
        heroFocus.target = !sheetOpen && zone == Z_HERO ? 1 : 0;
        if (snap) heroFocus.snap(heroFocus.target);
        boolean topChanged = aim(topFocus, !sheetOpen && zone == Z_TOP ? topIndex : -1, snap);
        boolean cardsChanged = aim(cardFocus, !sheetOpen && zone == Z_CARDS && tab != 1 ? cardIndex : -1, snap);
        boolean appTabChanged = retargetAppTab(snap);
        heroChanged |= cardsChanged | topChanged | appTabChanged;
        statusChanged |= topChanged;
        topBarDirty |= snap || topChanged;
        cardsDirty |= snap || cardsChanged | appTabChanged;
        tilesDirty |= snap || tilesChanged;
        statusDirty |= snap || statusChanged;
        heroDirty |= snap || heroChanged;
        drawerDirty |= snap || drawerChanged;
        scheduleFrame();
    }

    /** Aims a group's springs (only `focused` at 1); returns true if any target changed. */
    private static boolean aim(Spring[] springs, int focused, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < springs.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= springs[i].target != target;
            springs[i].target = target;
            if (snap) springs[i].snap(target);
        }
        return changed;
    }

    // MARK: - "All Apps" sheet

    private void setAllApps(List<HomeApps.Item> list) {
        allApps = list;
        appTabFocus = new Spring[list.size()];
        for (int i = 0; i < appTabFocus.length; i++) appTabFocus[i] = new Spring(0.28f, 0.82f, 0);
        appTabLabels = null;
        appTabIndex = Math.min(appTabIndex, Math.max(0, list.size() - 1));
        retargetAppTab(true);
        cardsDirty = true;
    }

    private boolean retargetAppTab(boolean snap) {
        boolean changed = false;
        int focused = (!sheetOpen && tab == 1 && zone == Z_CARDS) ? appTabIndex : -1;
        for (int i = 0; i < appTabFocus.length; i++) {
            float target = i == focused ? 1 : 0;
            changed |= appTabFocus[i].target != target;
            appTabFocus[i].target = target;
            if (snap) appTabFocus[i].snap(target);
        }
        int rows = Math.max(1, (allApps.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = appTabIndex / COLUMNS;
        float target = appTabScroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        float nextScroll = Math.max(0, Math.min(rows - visible, target));
        changed |= appTabScroll.target != nextScroll;
        appTabScroll.target = nextScroll;
        if (snap) appTabScroll.snap(appTabScroll.target);
        return changed;
    }

    private void drawAppsTab(RecordingCanvas c) {
        float morph = Ui.clamp(appsMorph.value);
        if (morph <= 0.01f) return;
        int n = allApps.size();
        if (n == 0) return;
        if (appTabLabels == null || appTabLabels.length != n) {
            appTabLabels = new String[n];
            for (int i = 0; i < n; i++) {
                appTabLabels[i] = TextUtils.ellipsize(allApps.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2f * k;
        float gridTop = 266 * k + (1f - morph) * 28 * k;
        float shift = appTabScroll.value * GRID_ROW * k;

        int save = c.save();
        c.clipRect(0, 236 * k, w, h);
        int topFocused = -1;
        for (int i = 0; i < n; i++) {
            if (appTabFocus[i].value > 0.02f && (topFocused < 0 || appTabFocus[i].value > appTabFocus[topFocused].value)) {
                topFocused = i;
            }
        }
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < n; i++) {
                if ((pass == 0) == (i == topFocused)) continue;
                int r = i / COLUMNS, col = i % COLUMNS;
                float y = gridTop + r * GRID_ROW * k - shift;
                if (y > h || y + GRID_ROW * k < 220 * k) continue;
                float x = left + col * (GRID_W + GRID_GAP) * k;
                tmp.set(x, y, x + GRID_W * k, y + GRID_H * k);
                float f = appTabFocus[i].value;
                drawCard(c, allApps.get(i).tile, tmp, GRID_RADIUS * k, f, 0.08f);
                if (tab == 1 && i == appTabIndex) focusedAppTabCell.set(cell);
                int labelBaseCol = tone(L_DIM, 0x99FFFFFF);
                int labelFocusCol = tone(L_INK, 0xFFFFFFFF);
                gridLabel.setColor(Ui.alpha(blend(labelBaseCol, labelFocusCol, Ui.clamp(f)), morph));
                c.drawText(appTabLabels[i], tmp.centerX(), tmp.bottom + (40 + 6 * f) * k, gridLabel);
            }
        }
        c.restoreToCount(save);
    }

    private void setOthers(List<HomeApps.Item> list) {
        others = list;
        gridFocus = new Spring[list.size()];
        for (int i = 0; i < gridFocus.length; i++) gridFocus[i] = new Spring(0.3f, 0.82f, 0);
        gridLabels = null;
        gridIndex = Math.min(gridIndex, Math.max(0, list.size() - 1));
        retargetGrid(true);
        gridDirty = true;
    }

    private void openSheet() {
        sheetOpen = true;
        gridIndex = 0;
        scroll.snap(0);
        retargetGrid(true);
        retarget(false);
        sheet.target = 1;
        gridDirty = true;
        scheduleFrame();
    }

    private void closeSheet() {
        sheetOpen = false;
        sheet.target = 0;
        retarget(false);
        scheduleFrame();
    }

    private void retargetGrid(boolean snap) {
        for (int i = 0; i < gridFocus.length; i++) {
            gridFocus[i].target = sheetOpen && i == gridIndex ? 1 : 0;
            if (snap) gridFocus[i].snap(gridFocus[i].target);
        }
        int rows = Math.max(1, (others.size() + COLUMNS - 1) / COLUMNS);
        int visible = Math.min(rows, VISIBLE_ROWS);
        int r = gridIndex / COLUMNS;
        float target = scroll.target;
        if (r < target) target = r;
        else if (r > target + visible - 1) target = r - visible + 1;
        scroll.target = Math.max(0, Math.min(rows - visible, target));
        if (snap) scroll.snap(scroll.target);
        scheduleFrame();
    }

    private void drawSheet(Canvas c) {
        c.drawColor(0xA8000000);
        float shift = scroll.value * GRID_ROW * k;
        c.drawText(Ui.tr("Todos os Apps", "All Apps"), w / 2f, 124 * k - shift, sheetTitle);
        int n = others.size();
        if (gridLabels == null || gridLabels.length != n) {
            gridLabels = new String[n];
            for (int i = 0; i < n; i++) {
                gridLabels[i] = TextUtils.ellipsize(others.get(i).label, gridLabel, (GRID_W - 10) * k, TextUtils.TruncateAt.END).toString();
            }
        }
        float gridWidth = COLUMNS * GRID_W + (COLUMNS - 1) * GRID_GAP;
        float left = (1920 - gridWidth) / 2 * k;
        float gridTop = GRID_TOP * k;
        for (int i = 0; i < n; i++) {
            int r = i / COLUMNS, col = i % COLUMNS;
            float y = gridTop + r * GRID_ROW * k - shift;
            if (y > h || y + GRID_ROW * k < 0) continue;
            float x = left + col * (GRID_W + GRID_GAP) * k;
            RectF base = new RectF(x, y, x + GRID_W * k, y + GRID_H * k);
            float f = gridFocus[i].value;
            drawCard(c, others.get(i).tile, base, GRID_RADIUS * k, f, 0.08f);
            if (sheetOpen && i == gridIndex) focusedCell.set(cell);
            gridLabel.setColor(blend(0x99FFFFFF, 0xFFFFFFFF, Ui.clamp(f)));
            c.drawText(gridLabels[i], base.centerX(), base.bottom + (40 + 6 * f) * k, gridLabel);
        }
    }

    /** 16:9 sheet card. Focused (the shelf's subtle focus): it grows, with a shadow, a soft sheen and a thin border. */
    private void drawCard(Canvas c, Bitmap image, RectF base, float radius, float f, float grow) {
        float s = 1 + grow * f;
        float cx = base.centerX(), cy = base.centerY();
        cell.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        float r = radius * s;
        float fc = Ui.clamp(f);
        if (f > 0.01f) {
            drawShadow(c, cx, cy, base.width(), base.height(), radius, 26 * k, s, 16 * k * f, Math.round(255 * 0.55f * fc));
        }
        if (image != null) c.drawBitmap(image, null, cell, bitmapPaint);
        if (f > 0.01f) {
            shaderMatrix.setScale(1, cell.height() * 0.5f);
            shaderMatrix.postTranslate(0, cell.top);
            unitSheen.setLocalMatrix(shaderMatrix);
            sheen.setShader(unitSheen);
            sheen.setAlpha(Math.round(255 * 0.1f * fc));
            c.drawRoundRect(cell, r, r, sheen);
            sheen.setAlpha(255);
        }
        if (fc < 0.99f) {
            veil.setColor(Ui.alpha(0xFF000000, 0.08f * (1 - fc)));
            c.drawRoundRect(cell, r, r, veil);
        }
        border.setColor(0x14FFFFFF);
        c.drawRoundRect(cell, r, r, border);
        if (f > 0.01f) {
            rim.setShader(null);
            rim.setColor(Ui.alpha(0xFFFFFFFF, 0.4f * fc));
            rim.setStrokeWidth(1.5f * k);
            c.drawRoundRect(cell, r, r, rim);
            rim.setStrokeWidth(2.5f * k);
        }
    }

    // MARK: - Toasts

    void showToast(String text) {
        toasts.add(text);
        scheduleFrame();
    }

    /** Advances the toast queue; returns true while a toast fades in or out (animation). */
    private boolean tickToast(long up) {
        if (toast != null && up - toastAt > TOAST_MS) {
            toast = null;
            toastLayer.invalidate();
        }
        if (toast == null && toastPaint != null) {
            String next = toasts.poll();
            if (next != null) {
                toast = layout(next);
                toastAt = up;
            }
        }
        if (toast == null) return false;
        long age = up - toastAt;
        boolean fading = age < 350 || age > TOAST_MS - 450;
        if (fading) toastLayer.invalidate();
        return fading;
    }

    private void drawToast(Canvas c) {
        if (toast == null) return;
        float age = (SystemClock.uptimeMillis() - toastAt) / 1000f;
        float a = Math.min(Ui.clamp(age / 0.3f), Ui.clamp((TOAST_MS / 1000f - age) / 0.4f));
        if (a <= 0) return;
        float padX = 28 * k, padY = 18 * k, icon = 26 * k, iconW = Symbols.width(getContext(), "bell", icon);
        float tw = toast.getWidth() + iconW + 16 * k, th = toast.getHeight();
        float toastTop = 64 * k - (1 - a) * 16 * k;
        tmp.set((w - tw) / 2f - padX, toastTop, (w + tw) / 2f + padX, toastTop + th + 2 * padY);
        int save = c.saveLayerAlpha(tmp.left - 4, tmp.top - 4, tmp.right + 4, tmp.bottom + 4, Math.round(255 * a));
        scene.glass(c, tmp, Math.min(tmp.height() / 2, 32 * k), SMOKE, 0.55f, 0, 0, 0.02f, 1);
        Symbols.draw(getContext(), c, "bell", tmp.left + padX + iconW / 2, tmp.centerY(), icon, 0xEBFFFFFF);
        c.translate(tmp.left + padX + iconW + 16 * k, tmp.top + padY);
        toast.draw(c);
        c.restoreToCount(save);
    }

    private StaticLayout layout(String text) {
        int max = Math.round(720 * k);
        int width = (int) Math.min(max, Math.ceil(Layout.getDesiredWidth(text, toastPaint)));
        return StaticLayout.Builder.obtain(text, 0, text.length(), toastPaint, Math.max(1, width))
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /** New notices from the main process (mode switches, the check after the TV boots): those from the last 24 h. */
    private void checkNotices() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            JSONArray list = HomeMode.state(c).optJSONArray("notices");
            if (list == null) return;
            long seen = ui.getLong("seenNotice", 0), newest = seen;
            long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
            final List<String> fresh = new ArrayList<>();
            for (int i = list.length() - 1; i >= 0; i--) {   // the list comes newest first
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                long at = o.optLong("at");
                if (at <= seen || at < cutoff) continue;
                fresh.add(o.optString("text"));
                newest = Math.max(newest, at);
            }
            if (newest > seen) ui.edit().putLong("seenNotice", newest).apply();
            if (!fresh.isEmpty()) handler.post(() -> { for (String t : fresh) showToast(t); });
        });
    }

    // MARK: - Lists (in the background, so returning to the home screen isn't delayed)

    private void reload() {
        final Context c = getContext().getApplicationContext();
        LOADER.execute(() -> {
            final List<HomeApps.Item> s = HomeApps.shelf(c, true);
            final List<HomeApps.Item> a = HomeApps.allTvApps(c);
            final List<HomeApps.Item> st = statusList(c);
            final List<HomeApps.Item> d = drawerList(c);
            final List<HomeApps.Item> o = HomeApps.others(c);
            handler.post(() -> {
                setShelf(s);
                setAllApps(a);
                setStatusItems(st);
                setDrawerItems(d);
                if (!sheetOpen) setOthers(o);
            });
        });
    }

    // MARK: - Keys: only the arrows, OK and Back (system keys pass through to the system)

    boolean onKey(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(0, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(0, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Opens on release, and only if the press happened here: the release of an OK pressed inside another
                // app opens nothing.
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else {
                    if (okDown && !e.isCanceled()) activate();
                    okDown = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else {
                    if (backDown && !e.isCanceled()) back();
                    backDown = false;
                }
                return true;
            default:
                return false;
        }
    }

    private void back() {
        if (sheetOpen) {
            closeSheet();
        } else if (zone == Z_DRAWER) {
            closeDrawer();
            retarget(false);
        } else if (tab == 3 || tab == 1) {
            if (zone == Z_CARDS) {
                zone = Z_TOP;
                topIndex = tab;
                retarget(false);
            } else {
                selectTab(0);
                zone = Z_SHELF;
                retarget(false);
            }
        } else if (zone != Z_SHELF) {
            zone = Z_SHELF;
            retarget(false);
        } else {
            return;
        }
        click.move();
    }

    private void move(int dx, int dy) {
        if (sheetOpen) {
            moveGrid(dx, dy);
            return;
        }
        if (moveLight(dx, dy)) return;
        switch (zone) {
            case Z_SHELF:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(shelf.size() - 1, shelfIndex + dx));
                    if (next == shelfIndex) return;
                    shelfIndex = next;
                } else if (dy < 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else if (!statusItems.isEmpty()) {
                        zone = Z_STATUS;
                        statusIndex = shelf.isEmpty() ? 0 : nearest(statusRects, tileRects[shelfIndex].centerX());
                    } else {
                        return;
                    }
                } else {
                    if (drawerItems.isEmpty()) return;
                    openDrawer();
                }
                break;
            case Z_HERO:
                if (dx != 0) {
                    if (!browse(dx)) return;
                } else if (dy < 0) {
                    if (statusItems.isEmpty()) return;
                    zone = Z_STATUS;
                    statusIndex = 0;
                } else {
                    zone = Z_SHELF;
                }
                break;
            case Z_STATUS:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(statusItems.size() - 1, statusIndex + dx));
                    if (next == statusIndex) return;
                    statusIndex = next;
                } else if (dy > 0) {
                    if (hero != null && hero.button) {
                        zone = Z_HERO;
                    } else {
                        zone = Z_SHELF;
                        if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, statusRects[statusIndex].centerX());
                    }
                } else {
                    return;
                }
                break;
            default:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(drawerItems.size() - 1, drawerIndex + dx));
                    if (next == drawerIndex) return;
                    drawerIndex = next;
                } else if (dy < 0) {
                    closeDrawer();
                } else {
                    return;
                }
                break;
        }
        retarget(false);
        click.move();
    }

    private static int nearest(RectF[] rects, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < rects.length; i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private void moveGrid(int dx, int dy) {
        int n = others.size();
        if (n == 0) return;
        int r = gridIndex / COLUMNS, col = gridIndex % COLUMNS;
        int rows = (n + COLUMNS - 1) / COLUMNS;
        int next = gridIndex;
        if (dx != 0) {
            int candidate = gridIndex + dx;
            if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) next = candidate;
        } else {
            int targetRow = r + dy;
            if (targetRow >= 0 && targetRow < rows) next = Math.min(n - 1, targetRow * COLUMNS + col);
        }
        if (next == gridIndex) return;
        gridIndex = next;
        retargetGrid(false);
        click.move();
        gridDirty = true;
    }

    private void activate() {
        HomeApps.Item it;
        RectF r;
        if (sheetOpen) {
            if (others.isEmpty()) return;
            it = others.get(gridIndex);
            r = focusedCell;
        } else if (zone == Z_HERO) {
            Recs.Pick p = currentPick();
            if (p == null || p.pkg == null) return;
            it = HomeApps.app(p.pkg, Recs.serviceName(p));
            r = heroButton;
        } else if (zone == Z_STATUS) {
            if (statusItems.isEmpty()) return;
            it = statusItems.get(statusIndex);
            r = statusRects[statusIndex];
        } else if (zone == Z_DRAWER) {
            if (drawerItems.isEmpty()) return;
            it = drawerItems.get(drawerIndex);
            r = drawerRects[drawerIndex];
        } else if (zone == Z_TOP) {
            if (topIndex < 4) {
                selectTab(topIndex);
                return;
            }
            it = lightButtons.get(topIndex - 4);
            r = topRects[topIndex];
        } else if (zone == Z_CARDS) {
            if (tab == 3) {
                List<FlamengoData.MatchResult> past = FlamengoData.get(getContext()).lastResults;
                if (cardIndex < 0 || cardIndex >= past.size()) return;
                click.open();
                FlamengoData.launchMatchHighlights(activity, past.get(cardIndex));
                return;
            }
            if (tab == 1) {
                if (allApps.isEmpty() || appTabIndex < 0 || appTabIndex >= allApps.size()) return;
                it = allApps.get(appTabIndex);
                r = focusedAppTabCell;
            } else {
                Recs.Pick p = cardIndex < cards.size() ? cards.get(cardIndex) : null;
                if (p == null || p.pkg == null) return;
                it = HomeApps.app(p.pkg, Recs.serviceName(p));
                r = cardRects[cardIndex];
            }
        } else {
            if (shelf.isEmpty()) return;
            it = shelf.get(shelfIndex);
            r = tileRects[shelfIndex];
        }
        click.open();
        if (it.kind == HomeApps.ALL_APPS) {
            openSheet();
            return;
        }
        if (it.kind == HomeApps.INPUTS) {
            openDrawer();
            for (int i = 0; i < drawerItems.size(); i++) {
                if (drawerItems.get(i).kind == HomeApps.INPUT) {
                    drawerIndex = i;
                    break;
                }
            }
            retarget(false);
            return;
        }
        boolean opened = HomeApps.open(activity, it, this, Math.round(r.left), Math.round(r.top),
                Math.round(r.width()), Math.round(r.height()));
        if (!opened) showToast(Ui.tr("Não deu para abrir ", "Couldn't open ") + it.label + ".");
    }

    // MARK: - Light design: title, tabs, cards and icon-only buttons

    private void layoutLight() {
        if (lTab == null) return;
        // Text tabs; the focus pill extends 26 px past the text (the first tab's text starts at the margin).
        float pad = 26 * k, tabTop = L_TABBAR_TOP * k, tabBottom = (L_TABBAR_TOP + L_TABBAR_H) * k;
        float x = MARGIN * k - pad;
        for (int i = 0; i < 4; i++) {
            float tw = Math.max(lTabOn.measureText(tabs()[i]), lTab.measureText(tabs()[i]));
            topRects[i].set(x, tabTop, x + tw + 2 * pad, tabBottom);
            x = topRects[i].right + 4 * k;
        }
        if (pillW.value == 0) {
            pillX.snap(topRects[tab].centerX());
            pillW.snap(topRects[tab].width());
        } else {
            pillX.target = topRects[tab].centerX();
            pillW.target = topRects[tab].width();
        }
        float b = L_BUTTON * k, right = CONTENT_R * k, cy = L_BUTTON_CY * k;
        for (int i = 2; i >= 0; i--) {
            topRects[4 + i].set(right - b, cy - b / 2, right, cy + b / 2);
            right -= b + L_BUTTON_GAP * k;
        }
        float top = L_CARDS_TOP * k, ch = L_CARD_H * k, g = L_CARD_GAP * k, left = MARGIN * k, tall = L_TALL_W * k;
        float wide = (CONTENT_R - MARGIN) * k - 2 * tall - 2 * g;
        cardRects[0].set(left, top, left + wide, top + ch);
        cardRects[1].set(cardRects[0].right + g, top, cardRects[0].right + g + tall, top + ch);
        cardRects[2].set(cardRects[1].right + g, top, cardRects[1].right + g + tall, top + ch);
        cacheCardStrings();
        heroDirty = statusDirty = topBarDirty = cardsDirty = true;
    }

    private void cacheCardStrings() {
        if (lCardTitle == null || lCardMeta == null) return;
        TextPaint tp = new TextPaint(lCardTitle);
        TextPaint mp = new TextPaint(lCardMeta);
        for (int i = 0; i < 3; i++) {
            Recs.Pick p = i < cards.size() ? cards.get(i) : null;
            float bw = cardRects[i].width();
            if (p != null && bw > 0) {
                cardTitles[i] = TextUtils.ellipsize(p.title == null ? "" : p.title, tp, bw, TextUtils.TruncateAt.END).toString();
                cardMetas[i] = i == 0 && p.meta != null && !p.meta.isEmpty()
                        ? TextUtils.ellipsize(p.meta, mp, bw, TextUtils.TruncateAt.END).toString()
                        : "";
            } else {
                cardTitles[i] = "";
                cardMetas[i] = "";
            }
        }
    }

    /** The three cards of the current tab (Explore, Movies, Shows) and the background taken from the first one. */
    private void updateCards() {
        List<Recs.Pick> list = recs.now(TAB_KIND[tab]);
        if (!samePicks(list, cards)) {
            cards = list;
            if (tab != 3 && tab != 1) {
                cardIndex = Math.min(cardIndex, Math.max(0, list.size() - 1));
                if (zone == Z_CARDS && list.isEmpty()) {
                    zone = Z_SHELF;
                    retarget(false);
                }
            }
        }
        cacheCardStrings();
        for (Recs.Pick p : recs.wanted()) recs.backdrop(p);   // all three tabs preloaded: switching tabs is instant
        Backdrop b = cards.isEmpty() || tab == 3 || tab == 1 ? null : recs.backdrop(cards.get(0));
        if (cards.isEmpty() || tab == 3 || tab == 1 || b != null) showBackdrop(b);
        heroDirty = cardsDirty = true;
        scheduleFrame();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        tabMorph.target = (index == 3) ? 1f : 0f;
        appsMorph.target = (index == 1) ? 1f : 0f;
        if (topRects[index] != null && topRects[index].width() > 0) {
            pillX.target = topRects[index].centerX();
            pillW.target = topRects[index].width();
        }
        if (index == 3) {
            flaCol1.snap(0f); flaCol1.target = 1f;
            flaCol2.snap(0f); flaCol2.target = 1f;
            flaCol3.snap(0f); flaCol3.target = 1f;
        }
        if ((tab == 3 || tab == 1) && drawerOpen) {
            drawerOpen = false;
            drawer.snap(0);
        }
        if (tab == 1) {
            appTabIndex = 0;
            appTabScroll.snap(0);
        } else if (tab != 3) {
            cardIndex = Math.min(cardIndex, Math.max(0, cards.size() - 1));
        } else {
            cardIndex = 0;
        }
        topBarDirty = cardsDirty = heroDirty = statusDirty = motionDirty = true;
        updateCards();
        sky.invalidate();
        status.invalidate();
        platter.invalidate();
        tiles.invalidate();
        if (tab == 3) {
            FlamengoData.get(getContext()).refresh(() -> {
                cardsDirty = heroDirty = true;
                heroLayer.invalidate();
                scheduleFrame();
            });
        }
        scheduleFrame();
    }

    /**
     * Background: the first card's photo, heavily blurred and almost white; without it, a light gradient. At night, the
     * same photo almost black (or a dark gradient), drawn on top during the transition.
     */
    private void drawLightSky(Canvas c) {
        long up = SystemClock.uptimeMillis();
        float morph = Ui.clamp(tabMorph.value);
        if (morph < 0.999f) {
            float e = bgFadeAt == 0 ? 1 : fadeEase(bgFadeAt, up);
            boolean fading = bgFadeAt != 0;
            if (bgTo == null || (fading && bgFrom == null)) {
                if (lightGradient == null) lightGradient = new LinearGradient(0, 0, 0, h, 0xFFF5F5F7, 0xFFE2E2E7, Shader.TileMode.CLAMP);
                if (nightGradient == null) nightGradient = new LinearGradient(0, 0, 0, h, 0xFF1C1C1E, 0xFF0B0B0D, Shader.TileMode.CLAMP);
                if (night < 0.999f) {
                    fill.setShader(lightGradient);
                    c.drawRect(screen, fill);
                }
                if (night > 0.001f) {
                    fill.setShader(nightGradient);
                    fill.setAlpha(Math.round(255 * night));
                    c.drawRect(screen, fill);
                    fill.setAlpha(255);
                }
                fill.setShader(null);
            }
            if (fading && bgFrom != null) drawLightImage(c, bgFrom, (bgTo == null ? 1 - e : 1) * (1 - morph));
            if (bgTo != null) drawLightImage(c, bgTo, (fading ? e : 1) * (1 - morph));
            float veilAmount = tone(0.32f, 0.42f) * Ui.clamp(drawer.value) * (1 - morph);
            if (veilAmount > 0.002f) {
                c.drawColor(Ui.alpha(blend(0xFFFFFFFF, 0xFF000000, night), veilAmount));
            }
        }
        if (morph > 0.001f) {
            int a = Math.round(255 * morph);
            fill.setAlpha(a);
            fill.setShader(flaSkyBg);
            c.drawRect(screen, fill);
            fill.setShader(flaGlowTop);
            c.drawRect(screen, fill);
            fill.setShader(flaGlowBot);
            c.drawRect(screen, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        drawLiveWeatherSky(c, up, morph);
    }

    /** 17: Live Weather Canvas (rain streaks, twinkling stars + shooting star, sunset golden glow, or crimson embers). */
    private void drawLiveWeatherSky(Canvas c, long up, float morph) {
        float t = up / 1000f;
        HomeData.Weather wx = data.weather;
        int code = wx.code;
        boolean rainy = (code >= 51 && code <= 67) || (code >= 80 && code <= 99);
        boolean stormy = code >= 95;
        long nowMs = System.currentTimeMillis();
        boolean nearSunset = wx.sunset > 0 && Math.abs(nowMs - wx.sunset) < 50 * 60_000L;

        // 1. Sunset golden/violet horizon glow when near sunset
        if (nearSunset && morph < 0.9f) {
            float sunAlpha = (1f - morph) * 0.35f;
            fill.setShader(new RadialGradient(w * 0.5f, h * 0.18f, w * 0.65f,
                    new int[]{Ui.alpha(0xFFFF7A33, sunAlpha), Ui.alpha(0xFF8A2BE2, sunAlpha * 0.5f), 0x00000000},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(screen, fill);
            fill.setShader(null);
        }

        // 2. Rain streaks if raining outside
        if (rainy) {
            fxPaint.setStyle(Paint.Style.STROKE);
            fxPaint.setStrokeCap(Paint.Cap.ROUND);
            fxPaint.setStrokeWidth(1.6f * k);
            for (int i = 0; i < 38; i++) {
                float seed = (i * 137.5f) % 1f;
                float speed = (780f + (i % 7) * 95f) * k;
                float y = ((t * speed + i * 97f * k) % (h + 120 * k)) - 60 * k;
                float x = ((i * 173f * k + (y * 0.22f)) % (w + 100 * k)) - 50 * k;
                float len = (24f + (i % 5) * 7f) * k;
                float alpha = (0.14f + 0.12f * seed) * (morph > 0.5f ? 0.85f : 1f);
                fxPaint.setColor(Ui.alpha(morph > 0.5f ? 0xFFFFB4B4 : 0xFFA5D8FF, alpha));
                c.drawLine(x, y, x - len * 0.22f, y + len, fxPaint);
            }
            if (stormy) {
                float flash = (float) Math.sin(t * 1.9f) * (float) Math.sin(t * 7.3f);
                if (flash > 0.88f) {
                    c.drawColor(Ui.alpha(0xFFFFFFFF, (flash - 0.88f) * 1.4f));
                }
            }
        }

        // 3. Night / Dark sky: twinkling stars + periodic shooting star (every ~16 seconds)
        float nightVis = Math.max(night, morph);
        if (nightVis > 0.1f) {
            fxPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 36; i++) {
                float sx = ((i * 293 + 47) % 1920) * k;
                float sy = ((i * 109 + 19) % 420) * k;
                float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * (1.4f + (i % 5) * 0.45f) + i));
                float r = (1.1f + (i % 3) * 0.65f) * k;
                int starCol = morph > 0.5f && (i % 3 == 0) ? 0xFFFF6B6B : 0xFFFFFFFF;
                fxPaint.setColor(Ui.alpha(starCol, nightVis * twinkle * 0.62f));
                c.drawCircle(sx, sy, r, fxPaint);
            }

            // Shooting star across the upper sky every 14 seconds
            float cycle = t % 14f;
            if (cycle < 1.15f) {
                float p = cycle / 1.15f;
                float startX = 1480 * k, startY = 48 * k;
                float dx = -620 * k, dy = 210 * k;
                float headX = startX + dx * p;
                float headY = startY + dy * p;
                float tailLen = 0.22f;
                float tailP = Math.max(0f, p - tailLen);
                float tailX = startX + dx * tailP;
                float tailY = startY + dy * tailP;
                float starAlpha = (float) Math.sin(p * Math.PI) * nightVis * 0.9f;
                int headCol = morph > 0.5f ? 0xFFFFD166 : 0xFFFFFFFF;
                fxPaint.setStyle(Paint.Style.STROKE);
                fxPaint.setStrokeWidth(2.4f * k);
                fxPaint.setShader(new LinearGradient(headX, headY, tailX, tailY,
                        Ui.alpha(headCol, starAlpha), 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawLine(headX, headY, tailX, tailY, fxPaint);
                fxPaint.setShader(null);
                fxPaint.setStyle(Paint.Style.FILL);
                fxPaint.setColor(Ui.alpha(0xFFFFFFFF, starAlpha));
                c.drawCircle(headX, headY, 2.6f * k, fxPaint);
            }
        }

        // 4. Floating crimson/gold embers on Flamengo tab (when morph > 0.05)
        if (morph > 0.05f) {
            fxPaint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 22; i++) {
                float speed = (28f + (i % 6) * 11f) * k;
                float y = h - ((t * speed + i * 83f * k) % (h * 0.95f));
                float sway = (float) Math.sin(t * 1.2f + i * 1.7f) * 18f * k;
                float x = ((i * 197 + 60) % 1920) * k + sway;
                float fadeY = smoothstep(0, 0.2f, y / h) * (1f - smoothstep(0.75f, 1f, (h - y) / h));
                float r = (1.6f + (i % 4) * 0.9f) * k;
                int emberCol = (i % 4 == 0) ? 0xFFFFB703 : 0xFFFF2A3C;
                fxPaint.setColor(Ui.alpha(emberCol, morph * fadeY * 0.48f));
                c.drawCircle(x, y, r, fxPaint);
            }
        }
    }

    private void drawLightImage(Canvas c, Backdrop b, float alpha) {
        if (night < 0.999f || b.nightImage == null) drawImage(c, b.lightImage, alpha);
        if (night > 0.001f) drawImage(c, b.nightImage, alpha * night);
    }

    private void drawLightTop(Canvas c) {
        int topH = Math.round(280 * k);
        float morph = Ui.clamp(tabMorph.value);
        if (topBarDirty || !topBarNode.hasDisplayList()) {
            RecordingCanvas rc = topBarNode.beginRecording(w, topH);
            try {
                applyTone();
                float tabShiftY = 34 * k * morph;
                float tabShiftX = 16 * k * morph;
                if (morph > 0.005f) {
                    flaHeaderTitle.setAlpha(Math.round(255 * morph));
                    flaHeaderSub.setAlpha(Math.round(255 * morph));
                    float slide = (1f - morph) * 28 * k;
                    rc.drawText("Flamengo", 96 * k - Ui.leftBearing(flaHeaderTitle, "Flamengo") + slide, 114 * k, flaHeaderTitle);
                    rc.drawText("Clube de Regatas do Flamengo", 96 * k + slide, 154 * k, flaHeaderSub);
                    flaHeaderTitle.setAlpha(255);
                    flaHeaderSub.setAlpha(255);
                }
                if (morph < 0.995f) {
                    String hello = Ui.tr("Para você", "For You");
                    lTitle.setAlpha(Math.round(255 * (1f - morph)));
                    rc.drawText(hello, MARGIN * k - Ui.leftBearing(lTitle, hello) - morph * 28 * k, L_TITLE_BASE * k, lTitle);
                    lTitle.setAlpha(255);
                }
                float focus = Ui.clamp(topFocus[tab].value);
                float pillVis = Math.max(focus, morph);
                if (pillVis > 0.01f && pillW.value > 0) {
                    float pcx = pillX.value + tabShiftX;
                    float pcy = topRects[tab].centerY() + tabShiftY;
                    float pw = pillW.value, ph = topRects[tab].height();
                    float s = 1 + 0.05f * focus;
                    cell.set(pcx - pw * s / 2, pcy - ph * s / 2, pcx + pw * s / 2, pcy + ph * s / 2);
                    float rr = cell.height() / 2;
                    drawShadow(rc, pcx, pcy, pw, ph, rr, 26 * k, s,
                            8 * k * Math.max(0.4f, pillVis), Math.round(255 * 0.35f * Math.max(0.5f, pillVis)));
                    fill.setShader(null);
                    fill.setColor(Ui.alpha(0xFFFFFFFF, pillVis));
                    rc.drawRoundRect(cell, rr, rr, fill);
                }
                for (int i = 0; i < tabs().length; i++) {
                    RectF r = topRects[i];
                    float cx = r.centerX() + tabShiftX, cy = r.centerY() + tabShiftY;
                    boolean isSel = i == tab;
                    Paint p = isSel ? lTabOn : lTab;
                    if (isSel) {
                        float dist = Math.abs(pillX.value - r.centerX()) / Math.max(1f, r.width());
                        float onPill = pillVis * Ui.clamp(1f - dist);
                        lTabOn.setColor(blend(tone(L_INK, WHITE), L_INK, onPill));
                    }
                    rc.drawText(tabs()[i], cx - p.measureText(tabs()[i]) / 2, cy + Ui.halfCap(p), p);
                }
                drawQuotes(rc);
            } finally {
                topBarNode.endRecording();
            }
            topBarDirty = false;
        }
        if (cardsDirty || !cardsNode.hasDisplayList()) {
            RecordingCanvas rc = cardsNode.beginRecording(w, h);
            try {
                applyTone();
                if (tab == 3 || morph > 0.01f) {
                    drawFlamengoTab(rc);
                }
                if (tab == 1 || appsMorph.value > 0.01f) {
                    drawAppsTab(rc);
                }
                if (tab != 3 && tab != 1) {
                    if (cards.isEmpty()) {
                        clockPaint.setColor(tone(L_INK, WHITE));
                        rc.drawText(clockText, (w - clockPaint.measureText(clockText)) / 2, 555 * k, clockPaint);
                        String day = Ui.date(System.currentTimeMillis());
                        lCardMeta.setColor(tone(L_DIM, 0xB3FFFFFF));
                        rc.drawText(day, (w - lCardMeta.measureText(day)) / 2, 625 * k, lCardMeta);
                    } else {
                        int top = -1;
                        for (int i = 0; i < 3; i++) if (cardFocus[i].value > 0.02f && (top < 0 || cardFocus[i].value > cardFocus[top].value)) top = i;
                        for (int i = 0; i < 3; i++) if (i != top) drawLightCard(rc, i);
                        if (top >= 0) drawLightCard(rc, top);
                    }
                }
            } finally {
                cardsNode.endRecording();
            }
            cardsDirty = false;
        }
        c.drawRenderNode(topBarNode);
        c.drawRenderNode(cardsNode);
    }

    /**
     * Recommendation card: the title's photo (already at the focused card size, so it stays sharp), rounded corners and
     * a soft shadow; when focused, it grows 5%, rises and lights up its border. Below it, the title (and on the large
     * card, genre · runtime · service).
     */
    private void drawLightCard(Canvas c, int i) {
        RectF base = cardRects[i];
        Recs.Pick p = i < cards.size() ? cards.get(i) : null;
        float bootDelay = i * 0.14f;
        float bootC = Ui.clamp((bootEntrance.value - bootDelay) / (1f - bootDelay));
        float morphHide = 1f - Ui.clamp(Math.max(tabMorph.value, appsMorph.value) * 1.4f);
        float vis = bootC * morphHide;
        if (vis <= 0.01f) return;
        float v = cardFocus[i].value, f = Ui.clamp(v), s = (0.94f + 0.06f * bootC) + 0.05f * v;
        float bw = base.width(), bh = base.height(), r0 = 18 * k;
        float cx = base.centerX() + (1f - bootC) * (24 * i * k);
        float cy = base.centerY() - 6 * k * v + (1f - bootC) * 48 * k + (1f - morphHide) * 32 * k;
        cell.set(cx - bw * s / 2, cy - bh * s / 2, cx + bw * s / 2, cy + bh * s / 2);
        float r = r0 * s;
        drawShadow(c, cx, cy, bw, bh, r0, 30 * k, s, (10 + 14 * f) * k,
                Math.round(255 * vis * (tone(0.14f, 0.32f) + tone(0.2f, 0.28f) * f)));
        Backdrop b = p == null ? null : recs.backdrop(p);
        Bitmap img = b == null ? null : (i == 0 ? b.wide : b.tall);
        if (img != null) {
            bitmapPaint.setAlpha(Math.round(255 * vis));
            c.drawBitmap(img, null, cell, bitmapPaint);
            bitmapPaint.setAlpha(255);
        } else {
            fill.setShader(null);
            fill.setColor(Ui.alpha(tone(0xFFDADADF, 0xFF2C2C2E), vis));
            c.drawRoundRect(cell, r, r, fill);
        }
        if (f > 0.01f) {
            shaderMatrix.setScale(cell.width(), cell.height());
            shaderMatrix.postTranslate(cell.left, cell.top);
            diagSheen.setLocalMatrix(shaderMatrix);
            sheen.setShader(diagSheen);
            sheen.setAlpha(Math.round(255 * 0.22f * f * vis));
            c.drawRoundRect(cell, r, r, sheen);
            sheen.setAlpha(255);

            shaderMatrix.setScale(1, cell.height());
            shaderMatrix.postTranslate(0, cell.top);
            unitRim.setLocalMatrix(shaderMatrix);
            rim.setShader(unitRim);
            rim.setAlpha(Math.round(255 * 0.9f * f * vis));
            c.drawRoundRect(cell, r, r, rim);
            rim.setAlpha(255);
        }
        if (p == null) return;
        float textY = base.bottom + (1f - bootC) * 36 * k;
        lCardTitle.setAlpha(Math.round(255 * vis));
        c.drawText(cardTitles[i], cx - bw / 2f, textY + 46 * k, lCardTitle);
        lCardTitle.setAlpha(255);
        if (i == 0 && !cardMetas[0].isEmpty()) {
            lCardMeta.setAlpha(Math.round(255 * vis));
            c.drawText(cardMetas[0], cx - bw / 2f, textY + 80 * k, lCardMeta);
            lCardMeta.setAlpha(255);
        }
    }

    private void drawCrest(Canvas c, Bitmap b, float cx, float cy, float size) {
        if (b == null || b.isRecycled()) return;
        float half = size / 2f;
        tmp.set(cx - half, cy - half, cx + half, cy + half);
        c.drawBitmap(b, null, tmp, bitmapPaint);
    }

    private void drawFlamengoTab(RecordingCanvas c) {
        FlamengoData fla = FlamengoData.get(getContext());
        float morph = Ui.clamp(tabMorph.value);
        if (morph <= 0.01f) return;
        float left = 96 * k, top = 276 * k + (1f - morph) * 32 * k;
        float right = 1824 * k, bottom = 996 * k + (1f - morph) * 32 * k;
        float r0 = 24 * k;

        // Card shadow & dark glass-obsidian fill
        drawShadow(c, (left + right) / 2f, (top + bottom) / 2f, right - left, bottom - top, r0, 34 * k, 1f, 14 * k, Math.round(0x77 * morph));
        cell.set(left, top, right, bottom);
        fill.setShader(flaCardBg);
        fill.setAlpha(Math.round(255 * morph));
        c.drawRoundRect(cell, r0, r0, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        // Subtle container border
        border.setColor(Ui.alpha(0x2E567191, morph));
        border.setStrokeWidth(1.5f * k);
        c.drawRoundRect(cell, r0, r0, border);

        Bitmap flaCrest = fla.getFlamengoCrest();

        // 3 Columns geometry with staggered spring slide-in from right to left
        float slide1 = (1f - flaCol1.value) * 68 * k;
        float slide2 = (1f - flaCol2.value) * 92 * k;
        float slide3 = (1f - flaCol3.value) * 116 * k;

        float c1Left = left + 40 * k + slide1, c1Right = left + 544 * k + slide1;
        float sep1X = left + 576 * k + slide1;
        float c2Left = left + 608 * k + slide2, c2Right = left + 1144 * k + slide2;
        float sep2X = left + 1176 * k + slide2;
        float c3Left = left + 1208 * k + slide3, c3Right = right - 40 * k + slide3;

        // Vertical column dividers
        c.drawLine(sep1X, top + 36 * k, sep1X, bottom - 36 * k, flaLine);
        c.drawLine(sep2X, top + 36 * k, sep2X, bottom - 36 * k, flaLine);

        // ================= COLUMN 1 (LEFT): BRASILEIRÃO 2026 / CLASSIFICAÇÃO (8 Rows) =================
        c.drawText("Brasileirão 2026", c1Left, top + 58 * k, flaSectionTitle);
        c.drawText("Classificação", c1Left, top + 90 * k, flaTournTitle);

        float colP  = c1Right - 186 * k;
        float colJ  = c1Right - 130 * k;
        float colV  = c1Right - 74 * k;
        float colSg = c1Right - 18 * k;

        float headY = top + 138 * k;
        c.drawText("#", c1Left + 10 * k, headY, flaTableHead);
        c.drawText("Time", c1Left + 52 * k, headY, flaTableHead);
        flaTableHead.setTextAlign(Paint.Align.CENTER);
        c.drawText("P", colP, headY, flaTableHead);
        c.drawText("J", colJ, headY, flaTableHead);
        c.drawText("V", colV, headY, flaTableHead);
        c.drawText("SG", colSg, headY, flaTableHead);
        flaTableHead.setTextAlign(Paint.Align.LEFT);

        c.drawLine(c1Left, top + 152 * k, c1Right, top + 152 * k, flaLine);

        int nStand = Math.min(8, fla.standings.size());
        float standRowH = 66 * k;
        flaTableNum.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < nStand; i++) {
            FlamengoData.StandingRow rk = fla.standings.get(i);
            float rTop = top + 158 * k + i * standRowH;
            float rMid = rTop + standRowH * 0.5f;

            if (rk.isFla) {
                tmp.set(c1Left - 8 * k, rTop + 4 * k, c1Right + 8 * k, rTop + standRowH - 4 * k);
                fill.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                        0xCC6E0913, 0xB846050C, Shader.TileMode.CLAMP));
                c.drawRoundRect(tmp, 10 * k, 10 * k, fill);
                fill.setShader(null);
                border.setColor(0xFFD92332);
                border.setStrokeWidth(1.6f * k);
                c.drawRoundRect(tmp, 10 * k, 10 * k, border);
            }

            c.drawText(rk.rank, c1Left + 10 * k, rMid + Ui.halfCap(flaTeamName), flaTeamName);

            Bitmap rkCrest = rk.isFla ? flaCrest : fla.getTeamCrest(rk.teamId);
            drawCrest(c, rkCrest, c1Left + 62 * k, rMid, 32 * k);

            flaTeamName.setColor(rk.isFla ? 0xFFFFFFFF : 0xFFE2E8F0);
            c.drawText(rk.name, c1Left + 88 * k, rMid + Ui.halfCap(flaTeamName), flaTeamName);

            flaTeamName.setColor(0xFFFFFFFF);
            flaTeamName.setTextAlign(Paint.Align.CENTER);
            c.drawText(rk.points, colP, rMid + Ui.halfCap(flaTeamName), flaTeamName);
            flaTeamName.setTextAlign(Paint.Align.LEFT);

            flaTableNum.setColor(rk.isFla ? 0xFFFFFFFF : 0xFFB8C7D9);
            c.drawText(rk.played, colJ, rMid + Ui.halfCap(flaTableNum), flaTableNum);
            c.drawText(rk.wins, colV, rMid + Ui.halfCap(flaTableNum), flaTableNum);
            c.drawText(rk.goalDiff, colSg, rMid + Ui.halfCap(flaTableNum), flaTableNum);
        }
        flaTableNum.setTextAlign(Paint.Align.LEFT);

        // ================= COLUMN 2 (CENTER): ÚLTIMOS JOGOS (5 Focusable Rows) =================
        c.drawText("Últimos jogos", c2Left, top + 58 * k, flaSectionTitle);
        c.drawLine(c2Left, top + 88 * k, c2Right, top + 88 * k, flaLine);

        int nPast = Math.min(5, fla.lastResults.size());
        float pastRowH = 118 * k;
        for (int i = 0; i < nPast; i++) {
            FlamengoData.MatchResult m = fla.lastResults.get(i);
            float rTop = top + 96 * k + i * pastRowH;
            float rBot = rTop + pastRowH;
            flaRowRects[i].set(c2Left - 10 * k, rTop + 4 * k, c2Right + 10 * k, rBot - 4 * k);

            if (i < nPast - 1) {
                c.drawLine(c2Left, rBot, c2Right, rBot, flaLine);
            }

            float f = Ui.clamp(cardFocus[i].value);
            if (f > 0.01f) {
                fill.setShader(new LinearGradient(flaRowRects[i].left, flaRowRects[i].top, flaRowRects[i].right, flaRowRects[i].bottom,
                        Ui.alpha(0xCC6E0913, f), Ui.alpha(0x88240A12, f), Shader.TileMode.CLAMP));
                c.drawRoundRect(flaRowRects[i], 12 * k, 12 * k, fill);
                fill.setShader(null);
                border.setColor(Ui.alpha(0xFFFF3B4E, f));
                border.setStrokeWidth(2f * k);
                c.drawRoundRect(flaRowRects[i], 12 * k, 12 * k, border);
            }

            // Competition label on top
            c.drawText(m.tournament, c2Left, rTop + 30 * k, flaMatchMeta);

            float matchY = rTop + 74 * k;
            // Home team crest & name
            Bitmap bHome = "819".equals(m.homeId()) ? flaCrest : fla.getTeamCrest(m.homeId());
            drawCrest(c, bHome, c2Left + 18 * k, matchY, 34 * k);
            flaTeamName.setColor(m.isHome ? 0xFFFFFFFF : 0xFFD5E0EC);
            c.drawText(m.homeTeam(), c2Left + 44 * k, matchY + Ui.halfCap(flaTeamName), flaTeamName);

            // Score ("3 - 0") centered
            flaTeamName.setColor(0xFFFFFFFF);
            flaTeamName.setTextAlign(Paint.Align.CENTER);
            c.drawText(m.formattedScore(), c2Left + 236 * k, matchY + Ui.halfCap(flaTeamName), flaTeamName);
            flaTeamName.setTextAlign(Paint.Align.LEFT);

            // Away team crest & name
            Bitmap bAway = "819".equals(m.awayId()) ? flaCrest : fla.getTeamCrest(m.awayId());
            drawCrest(c, bAway, c2Left + 302 * k, matchY, 34 * k);
            flaTeamName.setColor(!m.isHome ? 0xFFFFFFFF : 0xFFD5E0EC);
            c.drawText(m.awayTeam(), c2Left + 328 * k, matchY + Ui.halfCap(flaTeamName), flaTeamName);
            flaTeamName.setColor(0xFFFFFFFF);

            // V / E / D badge on the right
            int bColor = "V".equals(m.result) ? 0xFF1E8E3E : ("E".equals(m.result) ? 0xFF475569 : 0xFFC5221F);
            tmp.set(c2Right - 38 * k, matchY - 19 * k, c2Right, matchY + 19 * k);
            fill.setColor(bColor);
            c.drawRoundRect(tmp, 9 * k, 9 * k, fill);
            c.drawText(m.result, tmp.centerX(), matchY + Ui.halfCap(flaBadgeText), flaBadgeText);
        }

        // ================= COLUMN 3 (RIGHT): PRÓXIMOS JOGOS (5 Rows) =================
        c.drawText("Próximos jogos", c3Left, top + 58 * k, flaSectionTitle);
        c.drawLine(c3Left, top + 88 * k, c3Right, top + 88 * k, flaLine);

        int nFixtures = Math.min(5, fla.nextFixtures.size());
        float fixRowH = 118 * k;
        for (int i = 0; i < nFixtures; i++) {
            FlamengoData.Fixture fx = fla.nextFixtures.get(i);
            float rTop = top + 96 * k + i * fixRowH;
            float rBot = rTop + fixRowH;

            if (i < nFixtures - 1) {
                c.drawLine(c3Left, rBot, c3Right, rBot, flaLine);
            }

            // Competition label on top
            c.drawText(fx.tournament != null ? fx.tournament : "", c3Left, rTop + 30 * k, flaMatchMeta);

            float matchY = rTop + 74 * k;
            // Home team crest & name
            Bitmap bHome = "819".equals(fx.homeId) ? flaCrest : fla.getTeamCrest(fx.homeId);
            drawCrest(c, bHome, c3Left + 18 * k, matchY, 34 * k);
            flaTeamName.setColor("819".equals(fx.homeId) ? 0xFFFFFFFF : 0xFFD5E0EC);
            c.drawText(fx.homeTeam, c3Left + 44 * k, matchY + Ui.halfCap(flaTeamName), flaTeamName);

            // Stacked Date / Time + "-"
            float dateCx = c3Left + 244 * k;
            flaMatchMeta.setTextAlign(Paint.Align.CENTER);
            flaMatchMeta.setColor(0xFFD5E0EC);
            c.drawText(fx.datePart(), dateCx, matchY - 4 * k, flaMatchMeta);
            flaMatchMeta.setColor(0xFF8FA0B5);
            c.drawText(fx.timePart(), dateCx, matchY + 20 * k, flaMatchMeta);
            c.drawText("-", c3Left + 284 * k, matchY + Ui.halfCap(flaMatchMeta), flaMatchMeta);
            flaMatchMeta.setTextAlign(Paint.Align.LEFT);

            // Away team crest & name
            Bitmap bAway = "819".equals(fx.awayId) ? flaCrest : fla.getTeamCrest(fx.awayId);
            drawCrest(c, bAway, c3Left + 320 * k, matchY, 34 * k);
            flaTeamName.setColor("819".equals(fx.awayId) ? 0xFFFFFFFF : 0xFFD5E0EC);
            c.drawText(fx.awayTeam, c3Left + 346 * k, matchY + Ui.halfCap(flaTeamName), flaTeamName);
            flaTeamName.setColor(0xFFFFFFFF);
        }
    }

    /**
     * Live TV, HDMI Inputs and Settings: icon-only glass circles (light by day, smoked at night); the focused one turns
     * white with a dark icon (the tvOS focus) and its name appears below it.
     */
    private void drawLightButtons(Canvas c) {
        applyTone();
        for (int i = 0; i < 3; i++) {
            RectF r = topRects[4 + i];
            float f = Ui.clamp(topFocus[4 + i].value), s = 1 + 0.1f * f, rad = r.width() / 2 * s;
            cell.set(r.centerX() - rad, r.centerY() - rad, r.centerX() + rad, r.centerY() + rad);
            drawShadow(c, r.centerX(), r.centerY(), r.width(), r.height(), r.width() / 2, 26 * k, s, (6 + 8 * f) * k,
                    Math.round(255 * (tone(0.1f, 0.3f) + tone(0.14f, 0.2f) * f)));
            scene.liquid(c, cell, rad, tone(0xFFFFFFFF, SMOKE), tone(0.55f, 0.5f), tone(0.02f, 0.01f), 12 * k, 8 * k,
                    tone(0.6f, 0.45f), 1 - f);
            if (f > 0.01f) {
                fill.setShader(null);
                fill.setColor(Ui.alpha(0xFFFFFFFF, f));
                c.drawCircle(cell.centerX(), cell.centerY(), rad, fill);
            }
            Symbols.draw(getContext(), c, lightButtons.get(i).symbol, cell.centerX(), cell.centerY(), 27 * k * s,
                    blend(tone(L_INK, 0xEBFFFFFF), L_INK, f));
            if (f > 0.01f) {
                String label = lightButtons.get(i).label;
                float half = lTip.measureText(label) / 2;
                float x = Math.min(r.centerX(), CONTENT_R * k - half);
                lTip.setAlpha(Math.round(255 * f));
                c.drawText(label, x, r.bottom + (40 + 4 * f) * k, lTip);
            }
        }
    }

    private boolean moveLight(int dx, int dy) {
        switch (zone) {
            case Z_SHELF:
                if (dy >= 0 || shelf.isEmpty()) return false;
                if (cards.isEmpty() || tab == 3) {
                    zone = Z_TOP;
                    topIndex = tab;
                } else {
                    zone = Z_CARDS;
                    cardIndex = nearest(cardRects, cards.size(), tileRects[shelfIndex].centerX());
                }
                break;
            case Z_CARDS:
                if (tab == 1) {
                    int n = allApps.size();
                    if (n == 0) {
                        zone = Z_TOP;
                        topIndex = tab;
                        break;
                    }
                    int r = appTabIndex / COLUMNS, col = appTabIndex % COLUMNS;
                    int rows = (n + COLUMNS - 1) / COLUMNS;
                    if (dx != 0) {
                        int candidate = appTabIndex + dx;
                        if (candidate >= 0 && candidate < n && candidate / COLUMNS == r) {
                            appTabIndex = candidate;
                        } else {
                            return true;
                        }
                    } else if (dy < 0) {
                        if (r > 0) {
                            appTabIndex = (r - 1) * COLUMNS + col;
                        } else {
                            zone = Z_TOP;
                            topIndex = tab;
                        }
                    } else if (dy > 0) {
                        if (r + 1 < rows) {
                            appTabIndex = Math.min(n - 1, (r + 1) * COLUMNS + col);
                        } else {
                            return true;
                        }
                    }
                    break;
                }
                if (tab == 3) {
                    int nPast = Math.min(5, FlamengoData.get(getContext()).lastResults.size());
                    if (dy < 0) {
                        if (cardIndex > 0) {
                            cardIndex--;
                        } else {
                            zone = Z_TOP;
                            topIndex = tab;
                        }
                    } else if (dy > 0) {
                        if (cardIndex + 1 < nPast) {
                            cardIndex++;
                        } else {
                            return true;
                        }
                    } else {
                        return true;
                    }
                    break;
                }
                if (dx != 0) {
                    int next = Math.max(0, Math.min(cards.size() - 1, cardIndex + dx));
                    if (next == cardIndex) return true;
                    cardIndex = next;
                } else if (dy < 0) {
                    zone = Z_TOP;
                    topIndex = tab;     // as on Apple TV: moving up to the tabs focuses the selected tab
                } else {
                    zone = Z_SHELF;
                    if (!shelf.isEmpty()) shelfIndex = nearest(tileRects, tileRects.length, cardRects[cardIndex].centerX());
                }
                break;
            case Z_TOP:
                if (dx != 0) {
                    int next = Math.max(0, Math.min(6, topIndex + dx));
                    if (next == topIndex) return true;
                    topIndex = next;
                    if (topIndex < 4) selectTab(topIndex);   // like Apple TV tabs: focusing a tab selects it
                } else if (dy > 0) {
                    if (tab == 1) {
                        if (!allApps.isEmpty()) {
                            zone = Z_CARDS;
                        } else {
                            return true;
                        }
                    } else if (tab == 3) {
                        int nPast = Math.min(5, FlamengoData.get(getContext()).lastResults.size());
                        if (nPast > 0) {
                            zone = Z_CARDS;
                            cardIndex = 0;
                        } else {
                            return true;
                        }
                    } else if (cards.isEmpty()) {
                        zone = Z_SHELF;
                    } else {
                        zone = Z_CARDS;
                        cardIndex = nearest(cardRects, cards.size(), topRects[topIndex].centerX());
                    }
                } else {
                    return true;
                }
                break;
            default:
                return false;
        }
        retarget(false);
        click.move();
        return true;
    }

    private static int nearest(RectF[] rects, int count, float x) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < Math.min(count, rects.length); i++) {
            float d = Math.abs(rects[i].centerX() - x);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    // MARK: - Helpers

    private static float smoothstep(float e0, float e1, float x) {
        float t = Ui.clamp((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    private static int blend(int a, int b, float f) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
    }
}
