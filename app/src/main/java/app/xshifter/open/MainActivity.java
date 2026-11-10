package app.xshifter.open;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

@SuppressLint("MissingPermission")
public final class MainActivity extends Activity implements ConnectionService.UiListener {
  private boolean destroyed;
  private static final int BG = Design.BG,
      CARD = Design.SURFACE,
      INK = Design.INK,
      MUTED = Design.MUTED,
      ACCENT = Design.LIME;
  private Design design;
  private boolean wide, compact;
  private int lastRenderedTab = -1;
  private boolean telemetryRenderPending;
  private final Runnable telemetryRender = () -> {
    telemetryRenderPending = false;
    if (this.visible && this.tab != 2) render();
  };
  private LinearLayout cardRow;
  private int cardColumn;
  private final Map<String, String> formValues = new HashMap<>();
  private final List<EditText> formInputs = new ArrayList<>();
  private JSONObject editingOwner;
  private CheckBox editingCalibrated;
  private Spinner editingDerailleur, editingCassette;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout root, body;
  private ScrollView contentScroll;
  private TextView connection;
  private BleLink link;
  private ConnectionService connectionService;
  private boolean serviceBound, visible;
  private BatteryNotifications batteryNotifications;
  private BatteryState.Reading demoBattery;
  private TextView batteryPercent, batteryDescription, batteryCardPercent, batteryCardDescription;
  private LinearLayout batteryStrip;
  private ProgressBar batteryProgress;
  private ImageView batteryStripIcon;
  private Switch batteryMonitorSwitch;
  private TextView firmwareText, batteryTrendText;
  private Runnable notificationAction;
  private int demoBatteryStep;
  private final Runnable batteryRefresh =
      new Runnable() {
        @Override
        public void run() {
          if (visible) {
            batteryChanged();
            handler.postDelayed(this, 5000);
          }
        }
      };
  private final ServiceConnection serviceConnection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
          connectionService = ((ConnectionService.LocalBinder) binder).service();
          link = connectionService.transport();
          if (visible) restoreService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          connectionService = null;
          link = null;
          if (!demo) disconnected();
        }
      };
  private android.content.SharedPreferences prefs;
  private int tab, selectedGear = -1, liveGear = -1, position = -1, adjustment;
  private int[] livePositions;
  private boolean authenticated, bench, uploading, awaitingTable, demo;
  private long lastTelemetry;
  private String address = "",
      peer = "—",
      status = "Offline · profiles and demo available";
  private Protocol.TableReader reader = new Protocol.TableReader();
  private final LinkedHashMap<String, BluetoothDevice> foundDevices = new LinkedHashMap<>();
  private final ArrayList<String> log = new ArrayList<>();
  private final ArrayList<JSONObject> profiles = new ArrayList<>();
  private JSONObject draft;
  private int[] expected;
  private int transaction;
  private byte[] identity;
  private ListView scanList;
  private ArrayAdapter<String> scanAdapter;
  private final Runnable readTimeout =
      () -> {
        if (awaitingTable) {
          awaitingTable = false;
          expected = null;
          status("No complete gear-table readback. Changes are unverified.");
          render();
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    design = new Design(this);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    prefs = getSharedPreferences("xshifter", MODE_PRIVATE);
    batteryNotifications = new BatteryNotifications(this);
    serviceBound =
        bindService(new Intent(this, ConnectionService.class), serviceConnection, BIND_AUTO_CREATE);
    if (getIntent().getBooleanExtra("open_device", false)) tab = 3;
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    try {
      JSONArray list = new JSONArray(prefs.getString("profiles", "[]"));
      for (int i = 0; i < list.length(); i++) profiles.add(list.getJSONObject(i));
    } catch (JSONException ignored) {
    }
    draft = newDraft();
    render();
  }

  private JSONObject newDraft() {
    JSONObject p = new JSONObject();
    try {
      p.put("schema", 1)
          .put("name", "Rival 22 · my setup")
          .put("derailleur", 1)
          .put("cassette", 1)
          .put("count", 11)
          .put("positions", new JSONArray())
          .put("calibrated", false);
    } catch (JSONException ignored) {
    }
    return p;
  }

  private int dp(float x) {
    return design.dp(x);
  }

  private GradientDrawable shape(int color) {
    return design.panel(color, Design.LINE, 20);
  }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setTypeface(design.body);
    v.setIncludeFontPadding(false);
    v.setLineSpacing(dp(3), 1);
    v.setPadding(0, dp(4), 0, dp(4));
    return v;
  }

  private TextView display(String s, int size, int color) {
    TextView t = text(s, size, color);
    t.setTypeface(design.display);
    return t;
  }

  private void caption(LinearLayout parent, String title) {
    TextView t = text(title.toUpperCase(Locale.ROOT), 10, MUTED);
    t.setLetterSpacing(.14f);
    t.setPadding(0, dp(12), 0, dp(7));
    parent.addView(t);
  }

  private void label(String s) {
    cardRow = null;
    cardColumn = 0;
    TextView t = text(s, 12, MUTED);
    t.setPadding(dp(3), dp(4), dp(3), dp(16));
    body.addView(t);
  }

  private LinearLayout card(String title) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    c.setPadding(dp(18), dp(18), dp(18), dp(18));
    c.setBackground(shape(CARD));
    if (wide) {
      if (cardRow == null || cardColumn == 2) {
        cardRow = new LinearLayout(this);
        cardRow.setBaselineAligned(false);
        body.addView(cardRow, new LinearLayout.LayoutParams(-1, -2));
        cardColumn = 0;
      }
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.bottomMargin = dp(16);
      if (cardColumn == 0) lp.setMarginEnd(dp(8));
      else lp.setMarginStart(dp(8));
      cardRow.addView(c, lp);
      cardColumn++;
    } else {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = dp(16);
      body.addView(c, lp);
    }
    TextView heading = text(title, 17, INK);
    heading.setTypeface(Typeface.create(design.body, Typeface.BOLD));
    heading.setPadding(0, 0, 0, dp(14));
    c.addView(heading);
    return c;
  }

  private Button button(LinearLayout parent, String title, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setAllCaps(false);
    b.setTextColor(title.startsWith("Clear") ? 0xffffb7a1 : INK);
    b.setTypeface(Typeface.create(design.body, Typeface.BOLD));
    b.setTextSize(13);
    b.setLetterSpacing(.01f);
    b.setMinHeight(dp(52));
    b.setMinimumHeight(dp(52));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMaxLines(2);
    b.setPadding(dp(12), dp(12), dp(12), dp(12));
    b.setStateListAnimator(null);
    b.setBackground(design.interactive(Design.RAISED, Design.LINE, 12));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    parent.addView(b, lp);
    b.setOnClickListener(
        v -> {
          try {
            action.run();
          } catch (Exception e) {
            message("Could not complete action", e.getMessage());
          }
        });
    return b;
  }

  private void action(LinearLayout p, String title, Runnable r) {
    Button b = button(p, title, r);
    b.setTextColor(BG);
    b.setBackground(design.interactive(ACCENT, 0, 12));
  }

  private void pairedButtons(
      LinearLayout parent, String a, Runnable left, String b, Runnable right) {
    LinearLayout row = new LinearLayout(this);
    row.setBaselineAligned(false);
    Button first = button(row, a, left), second = button(row, b, right);
    LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1),
        r = new LinearLayout.LayoutParams(0, -2, 1);
    l.setMarginEnd(dp(5));
    r.setMarginStart(dp(5));
    l.topMargin = r.topMargin = dp(8);
    first.setLayoutParams(l);
    second.setLayoutParams(r);
    parent.addView(row);
  }

  private void gap(LinearLayout p) {
    Space s = new Space(this);
    p.addView(s, new LinearLayout.LayoutParams(1, dp(8)));
  }

  private EditText input(LinearLayout p, String hint, String value, boolean numeric) {
    String title = hint.split("\n")[0];
    caption(p, title);
    EditText e = new EditText(this);
    e.setTextColor(INK);
    e.setHintTextColor(0xff718589);
    e.setTextSize(14);
    e.setTypeface(design.body);
    e.setHint(
        hint.contains("\n")
            ? hint.substring(hint.indexOf('\n') + 1)
            : (numeric ? "0" : "Enter " + title.toLowerCase(Locale.ROOT)));
    e.setText(tab == 2 ? formValues.getOrDefault(hint, value) : value);
    e.setTag(hint);
    if (numeric)
      e.setInputType(
          InputType.TYPE_CLASS_NUMBER
              | InputType.TYPE_NUMBER_FLAG_DECIMAL
              | InputType.TYPE_NUMBER_FLAG_SIGNED);
    e.setMinHeight(dp(52));
    e.setPadding(dp(14), dp(14), dp(14), dp(14));
    e.setBackground(design.panel(BG, Design.LINE, 12));
    e.setOnFocusChangeListener(
        (v, focus) -> e.setBackground(design.panel(BG, focus ? ACCENT : Design.LINE, 12)));
    p.addView(e, new LinearLayout.LayoutParams(-1, -2));
    if (tab == 2) formInputs.add(e);
    return e;
  }

  private void stashForm() {
    if (editingOwner != draft) return;
    for (EditText e : formInputs) formValues.put((String) e.getTag(), e.getText().toString());
    if (editingCalibrated != null)
      formValues.put("calibrated", String.valueOf(editingCalibrated.isChecked()));
    if (editingDerailleur != null)
      formValues.put("derailleur", String.valueOf(editingDerailleur.getSelectedItemPosition()));
    if (editingCassette != null)
      formValues.put("cassette", String.valueOf(editingCassette.getSelectedItemPosition()));
  }

  private void navigate(int n) {
    if (tab == n) return;
    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    tab = n;
    render();
  }

  private LinearLayout navigation(boolean rail) {
    LinearLayout nav = new LinearLayout(this);
    nav.setOrientation(rail ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
    nav.setPadding(dp(rail ? 10 : 8), dp(8), dp(rail ? 10 : 8), dp(8));
    nav.setBackground(design.panel(CARD, Design.LINE, rail ? 22 : 24));
    String[] names = {"Ride", "Tune", "Profiles", "Device"};
    if (rail) {
      Design.Icon mark = new Design.Icon(this, 4, ACCENT);
      nav.addView(mark, new LinearLayout.LayoutParams(-1, dp(compact ? 36 : 48)));
      Space s = new Space(this);
      nav.addView(s, new LinearLayout.LayoutParams(1, dp(compact ? 8 : 20)));
    }
    for (int i = 0; i < 4; i++) {
      final int index = i;
      boolean selected = tab == i;
      LinearLayout item = new LinearLayout(this);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setGravity(Gravity.CENTER);
      item.setPadding(dp(4), dp(7), dp(4), dp(7));
      item.setBackground(design.interactive(selected ? 0xff2c3b29 : Color.TRANSPARENT, 0, 16));
      Design.Icon icon = new Design.Icon(this, i, selected ? ACCENT : MUTED);
      item.addView(icon, new LinearLayout.LayoutParams(dp(25), dp(25)));
      TextView t = text(names[i], 10, selected ? ACCENT : MUTED);
      t.setGravity(Gravity.CENTER);
      t.setPadding(0, dp(4), 0, 0);
      item.addView(t);
      item.setContentDescription(names[i]);
      item.setSelected(selected);
      item.setFocusable(true);
      item.setOnClickListener(v -> navigate(index));
      nav.addView(
          item,
          rail
              ? new LinearLayout.LayoutParams(-1, dp(compact ? 60 : 72))
              : new LinearLayout.LayoutParams(0, dp(60), 1));
    }
    return nav;
  }

  private void render() {
    if (destroyed) return;
    handler.removeCallbacks(telemetryRender);
    telemetryRenderPending = false;
    int restoreScroll =
        lastRenderedTab == tab && contentScroll != null && (tab != 2 || editingOwner == draft)
            ? contentScroll.getScrollY()
            : 0;
    stashForm();
    formInputs.clear();
    editingCalibrated = null;
    editingDerailleur = null;
    editingCassette = null;
    wide = getResources().getConfiguration().screenWidthDp >= 700;
    compact = wide && getResources().getConfiguration().screenHeightDp < 600;
    cardRow = null;
    cardColumn = 0;
    batteryCardPercent = null;
    batteryCardDescription = null;
    batteryProgress = null;
    batteryMonitorSwitch = null;
    firmwareText = null;
    batteryTrendText = null;
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    LinearLayout shell = new LinearLayout(this);
    shell.setBaselineAligned(false);
    root.addView(shell, new LinearLayout.LayoutParams(-1, 0, 1));
    if (wide) {
      LinearLayout rail = navigation(true);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(86), -1);
      lp.setMargins(dp(12), dp(12), 0, dp(12));
      shell.addView(rail, lp);
    }
    LinearLayout main = new LinearLayout(this);
    main.setOrientation(LinearLayout.VERTICAL);
    shell.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(22), dp(compact ? 8 : 16), dp(22), dp(compact ? 6 : 12));
    Design.Icon mark = new Design.Icon(this, 4, ACCENT);
    if (!wide) header.addView(mark, new LinearLayout.LayoutParams(dp(26), dp(26)));
    LinearLayout brand = new LinearLayout(this);
    brand.setOrientation(LinearLayout.HORIZONTAL);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setPadding(dp(wide ? 0 : 7), 0, 0, 0);
    TextView word = display("XSHIFTER", 23, INK);
    word.setLetterSpacing(.04f);
    brand.addView(word);
    TextView open = text(" / OPEN", 10, MUTED);
    open.setLetterSpacing(.16f);
    open.setPadding(dp(4), dp(6), 0, 0);
    if (getResources().getConfiguration().screenWidthDp >= 360
        && getResources().getConfiguration().fontScale <= 1.15f) brand.addView(open);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    TextView chip =
        text(
            demo ? "●  DEMO" : authenticated ? "●  CONNECTED" : "○  OFFLINE",
            9,
            demo || authenticated ? ACCENT : MUTED);
    chip.setPadding(dp(10), dp(7), dp(10), dp(7));
    chip.setBackground(
        design.panel(demo || authenticated ? 0xff243121 : Design.SURFACE, Design.LINE, 30));
    header.addView(chip);
    main.addView(header);
    connection = text(status, 10, MUTED);
    connection.setMaxLines(2);
    if (compact) {
      connection.setPadding(dp(12), 0, dp(12), 0);
      connection.setMaxLines(1);
      connection.setEllipsize(android.text.TextUtils.TruncateAt.END);
      header.addView(connection, 1, new LinearLayout.LayoutParams(0, -2, 1));
    } else {
      connection.setPadding(dp(24), 0, dp(24), dp(9));
      main.addView(connection);
    }
    addBatteryStrip(main);
    ScrollView scroll = new ScrollView(this);
    contentScroll = scroll;
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    FrameLayout center = new FrameLayout(this);
    body =
        new LinearLayout(this) {
          @Override
          protected void onMeasure(int width, int height) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(
                    Math.min(MeasureSpec.getSize(width), dp(1100)), MeasureSpec.getMode(width)),
                height);
          }
        };
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(wide ? 24 : 20), dp(12), dp(wide ? 24 : 20), dp(24));
    FrameLayout.LayoutParams content =
        new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    center.addView(body, content);
    scroll.addView(center);
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    String[] titles = {"Your ride.", "Dial it in.", "Built for your bike.", "Stay connected."};
    String[] subtitles = {
      "Every shift, exactly where it belongs.",
      "Small adjustments. A better ride.",
      "Your drivetrain. Your calibration.",
      "Your XShifter, at a glance."
    };
    TextView title = display(titles[tab], compact ? 28 : 36, INK);
    title.setPadding(dp(2), 0, 0, dp(5));
    body.addView(title);
    TextView subtitle = text(subtitles[tab], 12, MUTED);
    subtitle.setPadding(dp(2), 0, 0, dp(compact ? 12 : 22));
    body.addView(subtitle);
    if (tab == 0) ride();
    else if (tab == 1) tune();
    else if (tab == 2) profileScreen();
    else deviceScreen();
    if (!wide) {
      LinearLayout nav = navigation(false);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMargins(dp(16), 0, dp(16), dp(10));
      main.addView(nav, lp);
    }
    setContentView(root);
    scroll.getViewTreeObserver().addOnPreDrawListener(
        new android.view.ViewTreeObserver.OnPreDrawListener() {
          @Override
          public boolean onPreDraw() {
            scroll.getViewTreeObserver().removeOnPreDrawListener(this);
            scroll.scrollTo(0, restoreScroll);
            return true;
          }
        });
    if (lastRenderedTab != tab && android.animation.ValueAnimator.areAnimatorsEnabled()) {
      body.setAlpha(0);
      body.setTranslationY(dp(8));
      body.animate().alpha(1).translationY(0).setDuration(180).start();
    }
    lastRenderedTab = tab;
  }

  private void metric(LinearLayout row, String value, String title) {
    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    col.addView(display(value, 25, INK));
    TextView t = text(title.toUpperCase(Locale.ROOT), 9, MUTED);
    t.setLetterSpacing(.08f);
    col.addView(t);
    row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void ride() {
    LinearLayout hero = card("Live transmission");
    FrameLayout instrument =
        new FrameLayout(this) {
          @Override
          protected void onMeasure(int width, int height) {
            if (getChildCount() > 0) {
              FrameLayout.LayoutParams picture =
                  (FrameLayout.LayoutParams) getChildAt(0).getLayoutParams();
              picture.width =
                  Math.max(dp(70), Math.min(dp(180), MeasureSpec.getSize(width) - dp(136)));
            }
            super.onMeasure(width, height);
          }
        };
    int n = livePositions == null ? 11 : livePositions.length;
    CassetteView cassette = new CassetteView(this, n, liveGear);
    FrameLayout.LayoutParams image = new FrameLayout.LayoutParams(dp(180), -1, Gravity.END);
    instrument.addView(cassette, image);
    LinearLayout readout = new LinearLayout(this);
    readout.setOrientation(LinearLayout.VERTICAL);
    readout.setGravity(Gravity.CENTER_VERTICAL);
    TextView number =
        display(
            liveGear < 0 ? "—" : String.format(Locale.ROOT, "%02d", liveGear + 1),
            compact ? 76 : 108,
            ACCENT);
    number.setPadding(0, 0, 0, 0);
    number.setContentDescription(liveGear < 0 ? "No gear reported" : "Gear " + (liveGear + 1));
    readout.addView(number);
    TextView sub = text(demo ? "SIMULATED GEAR" : "REPORTED GEAR", 9, MUTED);
    sub.setLetterSpacing(.16f);
    readout.addView(sub);
    instrument.addView(readout, new FrameLayout.LayoutParams(dp(130), -1, Gravity.START));
    hero.addView(instrument, new LinearLayout.LayoutParams(-1, dp(compact ? 126 : 178)));
    hero.addView(
        new TravelView(this, livePositions, liveGear),
        new LinearLayout.LayoutParams(-1, dp(compact ? 30 : 44)));
    View divider = new View(this);
    divider.setBackgroundColor(Design.LINE);
    hero.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    gap(hero);
    LinearLayout stats = new LinearLayout(this);
    metric(stats, livePositions == null ? "—" : String.valueOf(livePositions.length), "gears");
    metric(
        stats,
        livePositions != null && liveGear >= 0 && liveGear < livePositions.length
            ? mm(livePositions[liveGear]) + " mm"
            : "—",
        "cable position");
    metric(stats, demo ? "DEMO" : bench ? "BENCH" : "READ", "mode");
    hero.addView(stats);
    gap(hero);
    if (livePositions != null && livePositions.length > 1)
      pairedButtons(
          hero,
          "−  Previous gear",
          () -> shiftTo(liveGear - 1),
          "Next gear  +",
          () -> shiftTo(liveGear + 1));
    if (!authenticated && !demo) {
      action(hero, "Find my XShifter", this::scan);
      button(hero, "Try the demo", this::startDemo);
    }
    if (livePositions != null && livePositions.length > 0) {
      LinearLayout gears = card("Choose your gear");
      gears.addView(text("Select a position to open its tuning controls.", 12, MUTED));
      GridLayout grid = new GridLayout(this);
      int available = getResources().getConfiguration().screenWidthDp;
      int columns = wide ? 4 : available < 360 ? 4 : 5;
      grid.setColumnCount(columns);
      for (int i = 0; i < livePositions.length; i++) {
        final int index = i;
        boolean current = i == liveGear;
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(
            design.interactive(current ? 0xff2e3d27 : BG, current ? ACCENT : Design.LINE, 12));
        TextView gear =
            display(String.format(Locale.ROOT, "%02d", i + 1), 26, current ? ACCENT : INK);
        gear.setGravity(Gravity.CENTER);
        tile.addView(gear);
        TextView length = text(mm(livePositions[i]), 9, MUTED);
        length.setGravity(Gravity.CENTER);
        tile.addView(length);
        GridLayout.LayoutParams gp =
            new GridLayout.LayoutParams(
                GridLayout.spec(i / columns), GridLayout.spec(i % columns, 1f));
        gp.width = 0;
        gp.height = dp(compact ? 58 : 72);
        gp.setMargins(dp(3), dp(5), dp(3), dp(3));
        grid.addView(tile, gp);
        tile.setFocusable(true);
        tile.setContentDescription(
            "Tune gear " + (i + 1) + ", " + mm(livePositions[i]) + " millimeters");
        tile.setOnClickListener(
            v -> {
              selectedGear = index;
              navigate(1);
            });
      }
      gears.addView(grid, new LinearLayout.LayoutParams(-1, -2));
      caption(gears, "CABLE POSITION · MM");
    } else {
      LinearLayout intro = card("Ready when you are");
      intro.addView(
          text(
              "Wake your XShifter and connect to read its current setup. Or explore the controls in"
                  + " demo mode.",
              13,
              MUTED));
      caption(intro, "START WITH A READ");
      intro.addView(text("Device changes stay locked until you enable Bench mode.", 12, MUTED));
    }
    LinearLayout setup = card("Drivetrain workspace");
    caption(setup, "PROFILE");
    setup.addView(display(draft.optString("name", "Custom setup"), 28, INK));
    setup.addView(
        text(
            draft.optInt("count", 11)
                + " gears · "
                + (draft.optBoolean("calibrated") ? "calibrated profile" : "calibration required"),
            12,
            MUTED));
    button(setup, "Edit drivetrain profile", () -> navigate(2));
    LinearLayout session = card(demo ? "Explore without connecting" : "Device controls");
    session.addView(
        text(
            demo
                ? "Demo gear positions are simulated. Nothing is sent to your bike."
                : "Read calibration, inspect firmware information and manage your device"
                    + " connection.",
            13,
            MUTED));
    button(session, "Open device settings", () -> navigate(3));
  }

  private void tune() {
    LinearLayout c = card("Gear calibration");
    if (livePositions == null || livePositions.length < 2) {
      c.addView(
          text(
              "Connect to read your gear positions, or try the demo to explore fine tuning.",
              13,
              MUTED));
      action(c, "Find my XShifter", this::scan);
      button(c, "Try the demo", this::startDemo);
      return;
    }
    if (selectedGear < 0 || selectedGear >= livePositions.length)
      selectedGear = Math.max(0, Math.min(liveGear, livePositions.length - 1));
    caption(c, "SELECT A GEAR");
    ArrayList<String> gs = new ArrayList<>();
    for (int i = 0; i < livePositions.length; i++)
      gs.add("Gear " + (i + 1) + " · " + mm(livePositions[i]) + " mm");
    Spinner select = spinner(c, gs, selectedGear);
    TextView readout = display(mm(livePositions[selectedGear]) + " mm", 46, ACCENT);
    readout.setPadding(0, dp(18), 0, dp(2));
    c.addView(readout);
    c.addView(text("Cable position at selected gear", 11, MUTED));
    select.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            selectedGear = n;
            if (livePositions != null && n >= 0 && n < livePositions.length)
              readout.setText(mm(livePositions[n]) + " mm");
          }
        });
    button(c, "Shift to selected gear", () -> shiftTo(selectedGear));
    caption(c, "FINE ADJUSTMENT");
    pairedButtons(c, "− 0.1 mm", () -> nudge(-60), "+ 0.1 mm", () -> nudge(60));
    caption(c, "COARSE ADJUSTMENT");
    pairedButtons(c, "− 0.5 mm", () -> nudge(-300), "+ 0.5 mm", () -> nudge(300));
    gap(c);
    action(
        c,
        "Save tuning to XShifter",
        () -> {
          requireBench();
          if (demo) {
            status("Demo tuning saved for this session");
            return;
          }
          send("Save gear positions", Protocol.commit());
          handler.postDelayed(this::safeRefresh, 1200);
        });
    c.addView(
        text(
            demo
                ? "Demo mode · changes are simulated."
                : "Shift to the selected gear before trimming it.",
            11,
            MUTED));
    LinearLayout slack = card("Cable length / slack");
    slack.addView(display(mm(adjustment) + " mm", 42, INK));
    slack.addView(text("Current cable-length adjustment", 12, MUTED));
    pairedButtons(
        slack, "− 0.1 mm cable", () -> adjustCable(-60), "+ 0.1 mm cable", () -> adjustCable(60));
    caption(slack, "KEEP YOUR CALIBRATION");
    slack.addView(text("Copy the table into a profile to review, save or export it.", 12, MUTED));
    button(
        slack,
        "Copy device table into profile",
        () -> {
          draft = newDraft();
          put(draft, "count", livePositions.length);
          put(draft, "positions", array(livePositions));
          put(draft, "calibrated", !demo);
          put(
              draft,
              "name",
              demo ? "Demo calibration · not for device use" : "Device calibration · " + address);
          navigate(2);
        });
    LinearLayout mode =
        card(
            demo
                ? "You're in demo mode"
                : bench ? "Bench controls enabled" : "Device changes are locked");
    mode.addView(
        text(
            demo
                ? "Explore every control without moving your bike's derailleur."
                : "Use Bench mode with the bike supported. Keep the cranks turning when shifting.",
            12,
            MUTED));
    button(mode, "Open device settings", () -> navigate(3));
  }

  private void profileScreen() {
    if (editingOwner != draft) {
      formValues.clear();
      editingOwner = draft;
    }
    LinearLayout c = card("Drivetrain profile");
    EditText name = input(c, "Profile name", draft.optString("name"), false);
    caption(c, "Rear derailleur");
    Spinner derailleur =
        spinner(
            c,
            Catalog.DERAILLEURS,
            Integer.parseInt(
                formValues.getOrDefault(
                    "derailleur",
                    String.valueOf(
                        Math.min(draft.optInt("derailleur", 1), Catalog.DERAILLEURS.size() - 1)))));
    editingDerailleur = derailleur;
    caption(c, "Cassette configuration");
    Spinner cassette =
        spinner(
            c,
            Catalog.CASSETTES,
            Integer.parseInt(
                formValues.getOrDefault(
                    "cassette",
                    String.valueOf(
                        Math.min(draft.optInt("cassette", 1), Catalog.CASSETTES.size() - 1)))));
    editingCassette = cassette;
    TextView compatibility = text("", 13, MUTED);
    compatibility.setPadding(dp(2), dp(12), dp(2), dp(2));
    c.addView(compatibility);
    AdapterView.OnItemSelectedListener update =
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            compatibility.setText(
                Catalog.constraint(
                    Catalog.DERAILLEURS.get(derailleur.getSelectedItemPosition()),
                    Catalog.CASSETTES.get(cassette.getSelectedItemPosition())));
          }
        };
    derailleur.setOnItemSelectedListener(update);
    cassette.setOnItemSelectedListener(update);
    EditText count =
        input(c, "Number of gears (2–17)", String.valueOf(draft.optInt("count", 11)), true);
    count.setInputType(InputType.TYPE_CLASS_NUMBER);
    button(
        c,
        "Use selected cassette's gear count",
        () -> {
          int speed = Catalog.CASSETTES.get(cassette.getSelectedItemPosition()).speed();
          if (speed == 0)
            throw new IllegalArgumentException("Enter your custom cassette's gear count directly");
          count.setText(String.valueOf(speed));
        });
    EditText teeth =
        input(
            c,
            "Optional sprocket teeth, smallest to largest",
            teethText(draft.optJSONArray("teeth")),
            false);
    EditText positions =
        input(
            c,
            "Cable positions in mm, smallest to largest\nExample: 0, 2.5, 5.1, …",
            positionsText(draft.optJSONArray("positions")),
            false);
    positions.setMinLines(3);
    positions.setGravity(Gravity.TOP);
    CheckBox calibrated = new CheckBox(this);
    calibrated.setText("These positions have been physically calibrated");
    calibrated.setTextColor(MUTED);
    calibrated.setTypeface(design.body);
    calibrated.setTextSize(12);
    calibrated.setMinHeight(dp(56));
    calibrated.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    calibrated.setChecked(
        Boolean.parseBoolean(
            formValues.getOrDefault("calibrated", String.valueOf(draft.optBoolean("calibrated")))));
    editingCalibrated = calibrated;
    c.addView(calibrated);
    TextWatcher invalidateCalibration =
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          @Override
          public void onTextChanged(CharSequence s, int start, int before, int count) {
            calibrated.setChecked(false);
          }

          @Override
          public void afterTextChanged(Editable s) {}
        };
    positions.addTextChangedListener(invalidateCalibration);
    count.addTextChangedListener(invalidateCalibration);
    Runnable capture =
        () -> {
          int n = Integer.parseInt(count.getText().toString());
          if (n < 2 || n > 17)
            throw new IllegalArgumentException("Choose 2–17 gears (original app range)");
          int[] p = parsePositions(positions.getText().toString());
          int[] sprockets = Catalog.parseTeeth(teeth.getText().toString(), n);
          if (p.length > 0) {
            Protocol.validatePositions(p);
            if (p.length != n) throw new IllegalArgumentException("Provide one position per gear");
          }
          if (name.getText().toString().trim().isEmpty())
            throw new IllegalArgumentException("Give this profile a name");
          put(draft, "name", name.getText().toString());
          put(draft, "count", n);
          put(draft, "teeth", array(sprockets));
          put(draft, "positions", array(p));
          put(draft, "derailleur", derailleur.getSelectedItemPosition());
          put(draft, "cassette", cassette.getSelectedItemPosition());
          put(draft, "calibrated", calibrated.isChecked());
        };
    action(
        c,
        "Save profile on phone",
        () -> {
          capture.run();
          saveProfile();
          render();
        });
    button(
        c,
        "Export profile…",
        () -> {
          capture.run();
          exportDraft();
        });
    button(
        c,
        "Apply calibrated table to XShifter…",
        () -> {
          capture.run();
          applyProfile();
        });
    LinearLayout generate = card("Calibration starting points");
    generate.addView(
        text(
            "Generate a linear draft between measured endpoints. This is a starting estimate, not a"
                + " SRAM or Shimano cable-pull specification.",
            14,
            MUTED));
    EditText start = input(generate, "Smallest-gear position (mm)", "0", true),
        end = input(generate, "Largest-gear position (mm)", "", true);
    button(
        generate,
        "Generate linear draft",
        () -> {
          int n = Integer.parseInt(count.getText().toString());
          if (n > 17) throw new IllegalArgumentException("Maximum 17 gears");
          int[] p =
              Protocol.linear(
                  n, units(start.getText().toString()), units(end.getText().toString()));
          positions.setText(positionsText(array(p)));
          calibrated.setChecked(false);
        });
    LinearLayout saved = card("Saved profiles");
    button(
        saved,
        "New profile",
        () -> {
          draft = newDraft();
          render();
        });
    button(
        saved,
        "Import profile…",
        () ->
            startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE),
                41));
    for (JSONObject p : profiles) {
      button(
          saved,
          p.optString("name"),
          () -> {
            try {
              draft = new JSONObject(p.toString());
              render();
            } catch (JSONException e) {
              throw new IllegalArgumentException("Invalid saved profile");
            }
          });
    }
    LinearLayout sources = card("Profile references");
    sources.addView(
        text(
            "Catalog data describes mechanical components. There are no factory-calibrated Rival"
                + " motor positions in this app.",
            13,
            MUTED));
    button(
        sources,
        "SRAM Rival 22 specifications",
        () -> open("https://www.sram.com/en/sram/models/rd-riv-b1"));
    button(
        sources,
        "SRAM Rival 1 specifications",
        () -> open("https://www.sram.com/en/sram/models/rd-riv-1-a2"));
  }

  private Spinner spinner(LinearLayout p, List<?> values, int selection) {
    Spinner s = new Spinner(this);
    ArrayList<String> labels = new ArrayList<>();
    for (Object value : values) labels.add(value.toString());
    s.setAdapter(
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
          @Override
          public View getView(int position, View convertView, ViewGroup parent) {
            TextView t = text(getItem(position) + "  ▾", 13, INK);
            t.setPadding(dp(14), dp(14), dp(12), dp(14));
            t.setMaxLines(2);
            t.setGravity(Gravity.CENTER_VERTICAL);
            return t;
          }

          @Override
          public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView t = text(getItem(position), 13, INK);
            t.setPadding(dp(16), dp(16), dp(16), dp(16));
            t.setMinHeight(dp(52));
            t.setBackgroundColor(CARD);
            return t;
          }
        });
    s.setSelection(Math.max(0, selection));
    s.setBackground(design.panel(BG, Design.LINE, 12));
    s.setPopupBackgroundDrawable(design.panel(CARD, Design.LINE, 12));
    s.setMinimumHeight(dp(52));
    p.addView(s, new LinearLayout.LayoutParams(-1, -2));
    return s;
  }

  private void deviceScreen() {
    batterySettings();
    LinearLayout c = card("Connection");
    c.addView(text(address.isEmpty() ? "No device selected" : address, 15, INK));
    firmwareText = text("", 14, MUTED);
    c.addView(firmwareText);
    deviceInfoChanged();
    c.addView(text("Pod / peer: " + peer, 14, MUTED));
    button(c, "Read firmware version", () -> {
      if (demo) { message("Live device required", "Connect to your XShifter to read its firmware version."); return; }
      if (connectionService == null) throw new IllegalStateException("Connect first");
      connectionService.requestFirmware();
    });
    action(c, "Find my XShifter", this::scan);
    button(c, "Read device state", this::refresh);
    button(
        c,
        "Disconnect",
        () -> {
          stopDemo();
          if (connectionService != null) connectionService.disconnect();
          status("Disconnected");
          render();
        });
    Switch mode = new Switch(this);
    mode.setText("Bench mode · enable device changes");
    mode.setTextColor(INK);
    mode.setTypeface(design.body);
    mode.setTextSize(12);
    mode.setMinHeight(dp(64));
    mode.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    mode.setChecked(bench);
    c.addView(mode);
    mode.setOnClickListener(
        v -> {
          if (!mode.isChecked()) {
            bench = false;
            return;
          }
          mode.setChecked(false);
          confirm(
              "Enable bench controls?",
              "Support the bike and keep the cranks turning during shifts. Commands are"
                  + " reverse-engineered from build 1105 and have not been tested on your device.",
              () -> {
                bench = true;
                mode.setChecked(true);
              });
        });
    LinearLayout data = card("Calibration backup");
    data.addView(
        text(
            "A local snapshot is captured before changing an existing gear table. Export it before"
                + " applying a new calibration.",
            14,
            MUTED));
    button(
        data,
        "Export last device backup…",
        () -> {
          String s =
              prefs.getString("preChange:" + address, prefs.getString("backup:" + address, null));
          if (s == null)
            throw new IllegalStateException("Read the device first to create a backup");
          try {
            draft = new JSONObject(s);
            exportDraft();
          } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid backup");
          }
        });
    button(
        data,
        "Phone identity",
        () ->
            message(
                "Connection identity",
                identity == null
                    ? "Created per device on first connection."
                    : Protocol.hex(identity) + "\nStored on this phone. It is not a password."));
    LinearLayout features = card("Available functions");
    features.addView(
        text(
            "Connect / read gear table / read version / read Pod info\n"
                + "Shift to gear / per-gear trim / cable adjustment\n"
                + "Save tuning / custom table upload / profile backup\n"
                + "Battery voltage / estimated level / low and critical alerts\n"
                + "Session voltage trend / possible fast-drain warning\n\n"
                + "Useful next additions: guided per-gear calibration, backup comparison and restore,"
                + " shift-response diagnostics, and exportable ride/battery history. These can use"
                + " existing commands; motor behaviour still needs device testing.\n\n"
                + "Firmware flashing is deferred until compatible firmware and device validation"
                + " are available. Pod button remapping, motor speed/force adjustment and"
                + " bike-computer gear broadcasting are not confirmed by the recovered protocol.",
            14,
            MUTED));
    button(
        features,
        "Clear device gear configuration…",
        () -> {
          requireBench();
          if (demo) throw new IllegalStateException("Unavailable in demo");
          confirm(
              "Clear all gear positions?",
              "This removes the device's current calibration. A local backup will be kept. You will"
                  + " need to restore a calibrated table before normal use.",
              () -> {
                requireBench();
                beforeChange();
                expected = null;
                send("Clear gear table", Protocol.clear());
                lastTelemetry = 0;
                handler.postDelayed(this::safeRefresh, 1500);
              });
        });
    LinearLayout diagnostics = card("Session log");
    for (String s : log) diagnostics.addView(text(s, 11, MUTED));
  }

  private BatteryState.Reading currentBattery() {
    return demo ? demoBattery : connectionService == null ? null : connectionService.battery();
  }

  private boolean batteryFresh() {
    BatteryState.Reading r = currentBattery();
    return r != null && (demo || authenticated && r.fresh(SystemClock.elapsedRealtime()));
  }

  private int batteryColor() {
    BatteryState.Reading r = currentBattery();
    if (r == null || !batteryFresh()) return MUTED;
    return r.percent() <= 10
        ? 0xffff8f7b
        : r.percent() <= prefs.getInt("battery_low_threshold", 20) ? 0xffffca76 : ACCENT;
  }

  private String batteryDetail() {
    BatteryState.Reading r = currentBattery();
    if (r == null)
      return demo
          ? "Demo · battery unavailable"
          : authenticated ? "Waiting for E-Link battery data" : "Connect to read E-Link battery";
    String state =
        demo
            ? "Demo"
            : !authenticated
                ? "Last reading · disconnected"
                : !r.fresh(SystemClock.elapsedRealtime())
                    ? "Stale reading"
                    : r.percent() <= 10
                        ? "Critical battery"
                        : r.percent() <= prefs.getInt("battery_low_threshold", 20)
                            ? "Low battery"
                            : "Battery estimate";
    return String.format(Locale.ROOT, "%s · %.2f V · estimated", state, r.volts());
  }

  private void addBatteryStrip(LinearLayout main) {
    batteryStrip = new LinearLayout(this);
    batteryStrip.setGravity(Gravity.CENTER_VERTICAL);
    batteryStrip.setPadding(dp(13), dp(9), dp(13), dp(9));
    ImageView icon = new ImageView(this);
    batteryStripIcon = icon;
    icon.setImageResource(R.drawable.ic_battery_notification);
    icon.setImageTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    batteryStrip.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(28)));
    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setPadding(dp(9), 0, dp(8), 0);
    TextView heading = text("E-Link battery", 11, INK);
    heading.setPadding(0, 0, 0, dp(2));
    labels.addView(heading);
    batteryDescription = text("", 10, MUTED);
    batteryDescription.setPadding(0, 0, 0, 0);
    labels.addView(batteryDescription);
    batteryStrip.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    batteryPercent = display("—", 28, ACCENT);
    batteryPercent.setPadding(0, 0, 0, 0);
    batteryStrip.addView(batteryPercent);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(dp(20), 0, dp(20), dp(7));
    main.addView(batteryStrip, lp);
    batteryStrip.setFocusable(true);
    batteryStrip.setOnClickListener(
        v -> {
          if (tab == 3 && contentScroll != null) contentScroll.smoothScrollTo(0, 0);
          else navigate(3);
        });
    batteryChanged();
  }

  @Override
  public void deviceInfoChanged() {
    if (firmwareText != null) firmwareText.setText("E-Link firmware: "
        + (demo ? "Unavailable in demo" : connectionService == null ? "Not connected" : connectionService.firmware())
        + (!demo && !authenticated ? " · disconnected" : ""));
  }

  @Override
  public void batteryChanged() {
    BatteryState.Reading r = currentBattery();
    int color = batteryColor();
    String value = r == null ? "—" : "~" + r.percent() + "%";
    String detail = batteryDetail();
    if (batteryPercent != null) {
      batteryPercent.setText(value);
      batteryPercent.setTextColor(color);
      batteryStripIcon.setImageTintList(android.content.res.ColorStateList.valueOf(color));
      batteryDescription.setText(detail);
      batteryStrip.setBackground(design.interactive(CARD, color, 14));
      batteryStrip.setContentDescription(
          "E-Link battery " + value + ", " + detail + ". Open battery settings.");
    }
    if (batteryCardPercent != null) {
      batteryCardPercent.setText(value);
      batteryCardPercent.setTextColor(color);
      batteryCardDescription.setText(
          detail
              + "\n"
              + "Percentage uses the original app's battery-icon voltage thresholds; it is not a"
              + " measured remaining-runtime value.");
      batteryProgress.setVisibility(r == null ? View.GONE : View.VISIBLE);
      batteryProgress.setProgress(r == null ? 0 : r.percent());
      batteryProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(color));
    }
    if (batteryMonitorSwitch != null)
      batteryMonitorSwitch.setChecked(connectionService != null && connectionService.monitoring());
    if (batteryTrendText != null) {
      BatteryTrend.Result trend = connectionService == null || demo ? null : connectionService.batteryTrend();
      String trendText = demo ? "Trend unavailable in demo" : !authenticated ? "Connect to collect a battery trend"
          : trend == null || !trend.ready() ? "Collecting voltage trend · needs 10 minutes of continuous readings"
          : String.format(Locale.ROOT, "%s · %+.2f V over %.0f minutes",
              trend.fastDrop() ? "Possible fast drain" : "Voltage trend", -trend.dropVolts(), trend.minutes());
      batteryTrendText.setText(trendText + "\nSession only. A sustained drop of at least 0.20 V in a 10–16 minute window flags possible drain. Load, temperature and charge can affect voltage; capacity and battery health are not measured. Alerts use Battery notifications and are limited to one per connection.");
      batteryTrendText.setTextColor(trend != null && trend.fastDrop() ? 0xffffa18b : MUTED);
    }
  }

  private void batterySettings() {
    LinearLayout c = card("E-Link battery");
    batteryCardPercent = display("—", 56, ACCENT);
    c.addView(batteryCardPercent);
    batteryCardDescription = text("", 12, MUTED);
    c.addView(batteryCardDescription);
    batteryProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    batteryProgress.setMax(100);
    c.addView(batteryProgress, new LinearLayout.LayoutParams(-1, dp(8)));
    batteryTrendText = text("", 12, MUTED);
    c.addView(batteryTrendText);
    gap(c);
    Switch monitor = new Switch(this);
    batteryMonitorSwitch = monitor;
    monitor.setText("Battery notifications");
    monitor.setTextColor(INK);
    monitor.setTypeface(design.body);
    monitor.setTextSize(13);
    monitor.setMinHeight(dp(56));
    monitor.setChecked(connectionService != null && connectionService.monitoring());
    monitor.setThumbTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    c.addView(monitor);
    c.addView(
        text(
            "Opt in for this connection to keep battery monitoring active while the phone is"
                + " locked. A persistent notification lets you stop it. Alerts stop when Bluetooth"
                + " disconnects.",
            12,
            MUTED));
    monitor.setOnClickListener(
        v -> {
          boolean enable = monitor.isChecked();
          monitor.setChecked(false);
          if (!enable) {
            if (connectionService != null) connectionService.stopMonitoring();
            render();
            return;
          }
          if (demo || !authenticated || connectionService == null) {
            message(
                "Connect first",
                "Connect to your real XShifter to enable battery monitoring. Demo battery values"
                    + " never trigger live alerts.");
            return;
          }
          requestNotifications(
              () -> {
                connectionService.enableMonitoring();
                render();
              });
        });
    caption(c, "LOW BATTERY WARNING");
    List<String> thresholds =
        List.of(
            "15% estimated", "20% estimated", "25% estimated", "30% estimated", "40% estimated");
    int[] values = {15, 20, 25, 30, 40};
    int current = prefs.getInt("battery_low_threshold", 20), selection = 1;
    for (int i = 0; i < values.length; i++) if (values[i] == current) selection = i;
    Spinner threshold = spinner(c, thresholds, selection);
    threshold.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            prefs.edit().putInt("battery_low_threshold", values[n]).apply();
            batteryChanged();
          }
        });
    c.addView(
        text(
            "Critical warning at ~10%. Repeated alerts are suppressed until the battery recovers."
                + " Unknown and stale readings never generate low-battery warnings.",
            12, MUTED));
    button(
        c,
        "Send test notification",
        () ->
            requestNotifications(
                () -> {
                  batteryNotifications.test();
                  status("Test notification sent · no live battery warning");
                }));
    if (!batteryNotifications.allowed())
      button(
          c,
          "Android notification settings",
          () ->
              startActivity(
                  new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                      .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
    if (demo)
      button(
          c,
          "Preview next battery state (demo)",
          () -> {
            int[] levels = {1200, 1110, 1060, 0, 1240};
            demoBatteryStep = (demoBatteryStep + 1) % levels.length;
            demoBattery =
                levels[demoBatteryStep] == 0
                    ? null
                    : new BatteryState.Reading(
                        levels[demoBatteryStep],
                        SystemClock.elapsedRealtime(),
                        System.currentTimeMillis());
            batteryChanged();
          });
    batteryChanged();
  }

  private void requestNotifications(Runnable action) {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      notificationAction = action;
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 80);
      return;
    }
    if (!batteryNotifications.allowed()) {
      message(
          "Notifications are disabled",
          "Enable low-battery notifications in Android notification settings. Battery status"
              + " remains visible in the app.");
      return;
    }
    try {
      action.run();
    } catch (RuntimeException e) {
      message("Battery notifications", e.getMessage());
    }
  }

  private void scan() {
    if (link == null)
      throw new IllegalStateException("Bluetooth is starting; try again in a moment");
    if (demo) stopDemo();
    String[] permissions =
        Build.VERSION.SDK_INT >= 31
            ? new String[] {
              Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            }
            : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    for (String p : permissions)
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(permissions, 7);
        return;
      }
    foundDevices.clear();
    scanAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
    scanList = new ListView(this);
    scanList.setAdapter(scanAdapter);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Nearby XShifters")
            .setMessage("Wake your device and close the original app. Searching for 12 seconds.")
            .setView(scanList)
            .setNegativeButton("Close", (d, w) -> link.stopScan())
            .create();
    scanList.setOnItemClickListener(
        (p, v, n, id) -> {
          BluetoothDevice device = new ArrayList<>(foundDevices.values()).get(n);
          dialog.dismiss();
          connect(device);
        });
    dialog.setOnDismissListener(d -> link.stopScan());
    dialog.show();
    link.scan();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] p, int[] grants) {
    super.onRequestPermissionsResult(code, p, grants);
    if (code == 80) {
      Runnable action = notificationAction;
      notificationAction = null;
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && action != null) {
        try {
          action.run();
        } catch (RuntimeException e) {
          message("Notifications", e.getMessage());
        }
      } else status("Notifications not enabled. In-app battery status is still available.");
      if (tab == 3) render();
      return;
    }
    if (code == 7) {
      for (int g : grants)
        if (g != PackageManager.PERMISSION_GRANTED) {
          message(
              "Bluetooth permission needed",
              "Allow Nearby devices / location permission to scan for your XShifter.");
          return;
        }
      scan();
    }
  }

  @Override
  public void found(BluetoothDevice device, int rssi) {
    if (foundDevices.putIfAbsent(device.getAddress(), device) == null && scanAdapter != null)
      scanAdapter.add(
          (device.getName() == null ? "XShifter" : device.getName())
              + "\n"
              + device.getAddress()
              + " · "
              + rssi
              + " dBm");
  }

  private void connect(BluetoothDevice device) {
    stopDemo();
    if (connectionService == null) throw new IllegalStateException("Bluetooth is still starting");
    connectionService.connect(device);
    address = connectionService.address();
    identity = connectionService.identity();
    render();
  }

  @Override
  public void ready() {
    status("Connected · identifying app");
  }

  @Override
  public void status(String text) {
    status = text;
    if (connection != null) connection.setText(text);
    record(text);
  }

  @Override
  public void disconnected() {
    authenticated = false;
    bench = false;
    uploading = false;
    awaitingTable = false;
    expected = null;
    transaction++;
    handler.removeCallbacks(readTimeout);
    livePositions = null;
    liveGear = -1;
    position = -1;
    lastTelemetry = 0;
    if (body != null) render();
  }

  private void record(String s) {
    log.add(0, java.time.LocalTime.now().withNano(0) + "  " + s);
    while (log.size() > 35) log.remove(log.size() - 1);
  }

  @Override
  public void packet(byte[] b) {
    record("RX " + Protocol.hex(b));
    if (b.length >= 3 && b[0] == 1 && b[1] == 1 && b[2] == 1) {
      batteryChanged();
      return;
    }
    int previousGear = liveGear;
    int previousPosition = position;
    int previousAdjustment = adjustment;
    int[] previousPositions = livePositions;
    String previousPeer = peer;
    try {
      if (b.length == 18 && Protocol.u(b[0]) == 0xdf && Protocol.u(b[1]) == 0x89) {
        authenticated = true;
        status("XShifter connected · reading calibration");
        refresh();
        render();
        return;
      }
      if (b.length == 3 && Protocol.u(b[0]) == 0xee && Protocol.u(b[1]) == 0xce) {
        liveGear = Protocol.u(b[2]);
        lastTelemetry = SystemClock.elapsedRealtime();
      }
      if (b.length == 10 && Protocol.u(b[0]) == 0xde && Protocol.u(b[1]) == 0xde) {
        position = Protocol.le(b, 2);
      }
      if (b.length >= 18 && Protocol.u(b[0]) == 0xaa && Protocol.u(b[1]) == 0xcf)
        peer =
            "ID "
                + Protocol.hex(Arrays.copyOfRange(b, 3, 11))
                + " · "
                + new String(Arrays.copyOfRange(b, 12, b.length), StandardCharsets.US_ASCII)
                    .replaceAll("[^0-9A-Za-z.\\-]", "");
      if (awaitingTable) {
        int[] table = reader.accept(b);
        if (table != null) {
          livePositions = table;
          adjustment = reader.adjustment;
          liveGear = reader.gear;
          lastTelemetry = SystemClock.elapsedRealtime();
          awaitingTable = false;
          handler.removeCallbacks(readTimeout);
          if (expected != null) {
            boolean matches = Arrays.equals(expected, table);
            expected = null;
            status(
                matches
                    ? "Calibration readback matches · power-cycle persistence not tested"
                    : "Readback differs from proposed table · review before riding");
          } else status("Calibration read from XShifter");
          if (table.length > 0) backup();
        }
      }
      boolean displayChanged =
          previousGear != liveGear
              || previousPosition != position
              || previousAdjustment != adjustment
              || !Arrays.equals(previousPositions, livePositions)
              || !previousPeer.equals(peer);
      if (displayChanged && tab != 2 && !telemetryRenderPending) {
        telemetryRenderPending = true;
        handler.postDelayed(telemetryRender, 100);
      }
    } catch (IllegalArgumentException e) {
      status("Rejected malformed device data: " + e.getMessage());
    }
  }

  private void send(String name, byte[] bytes) {
    link.send(bytes);
    record("TX " + name + " · " + Protocol.hex(bytes));
  }

  private void refresh() {
    if (demo) {
      render();
      return;
    }
    if (!authenticated)
      throw new IllegalStateException("Connect and wait for the device login response");
    if (uploading) throw new IllegalStateException("Wait for the table transfer");
    if (awaitingTable) return;
    reader = new Protocol.TableReader();
    awaitingTable = true;
    lastTelemetry = 0;
    send("Read gear table", Protocol.requestCogs());
    if (connectionService != null) connectionService.requestFirmware();
    send("Read Pod info", Protocol.peer());
    handler.removeCallbacks(readTimeout);
    handler.postDelayed(readTimeout, 10000);
  }

  private void safeRefresh() {
    if (authenticated && link.isReady() && !uploading) {
      try {
        refresh();
      } catch (RuntimeException e) {
        status("Readback failed: " + e.getMessage());
      }
    }
  }

  private void requireBench() {
    if (demo) return;
    if (!authenticated || link == null || !link.isReady())
      throw new IllegalStateException("Connect to your XShifter first");
    if (!bench) throw new IllegalStateException("Enable Bench mode on the Device screen");
    if (uploading || awaitingTable || !link.isIdle())
      throw new IllegalStateException("Wait for pending device operations");
    if (livePositions == null) throw new IllegalStateException("Read the device gear table first");
    if (SystemClock.elapsedRealtime() - lastTelemetry > 15000) {
      refresh();
      throw new IllegalStateException("Refreshing device state; try again after readback");
    }
  }

  private void shiftTo(int index) {
    requireBench();
    if (livePositions == null || index < 0 || index >= livePositions.length)
      throw new IllegalArgumentException("No gear in that direction");
    selectedGear = index;
    if (demo) {
      liveGear = index;
      render();
      return;
    }
    send("Select gear " + (index + 1), Protocol.shift(index, livePositions.length));
    lastTelemetry = 0;
    handler.postDelayed(this::safeRefresh, 1300);
  }

  private void nudge(int delta) {
    requireBench();
    if (selectedGear != liveGear)
      throw new IllegalStateException("Shift to the selected gear first");
    int[] proposed = livePositions.clone();
    proposed[selectedGear] += delta;
    Protocol.validatePositions(proposed);
    if (demo) {
      livePositions = proposed;
      render();
      return;
    }
    beforeChange();
    send("Trim gear " + (selectedGear + 1), Protocol.move(selectedGear, delta, false));
    expected = proposed;
    lastTelemetry = 0;
    handler.postDelayed(this::safeRefresh, 1300);
  }

  private void adjustCable(int delta) {
    requireBench();
    if (adjustment + delta < 0 || adjustment + delta > Protocol.MAX_POSITION)
      throw new IllegalArgumentException("Cable adjustment outside travel limit");
    if (demo) {
      adjustment += delta;
      render();
      return;
    }
    beforeChange();
    send("Adjust cable length", Protocol.cable(delta));
    lastTelemetry = 0;
    handler.postDelayed(this::safeRefresh, 1300);
  }

  private void beforeChange() {
    backup();
    prefs
        .edit()
        .putString("preChange:" + address, prefs.getString("backup:" + address, ""))
        .apply();
  }

  private void backup() {
    if (livePositions == null || livePositions.length < 2) return;
    JSONObject p = newDraft();
    put(p, "name", "Backup · " + address);
    put(p, "count", livePositions.length);
    put(p, "positions", array(livePositions));
    put(p, "calibrated", true);
    put(p, "device", address);
    put(p, "adjustment", adjustment);
    prefs.edit().putString("backup:" + address, p.toString()).apply();
  }

  private void applyProfile() {
    if (demo) throw new IllegalStateException("Exit demo and connect to apply a profile");
    requireBench();
    if (!draft.optBoolean("calibrated"))
      throw new IllegalArgumentException("Calibrate and confirm every position before applying");
    int[] p = fromArray(draft.optJSONArray("positions"));
    Protocol.validatePositions(p);
    if (p.length != draft.optInt("count") || p.length < 3 || p.length > 17)
      throw new IllegalArgumentException("Live table upload supports 3–17 calibrated gears");
    confirm(
        "Replace XShifter calibration?",
        "This writes "
            + p.length
            + " gear positions from “"
            + draft.optString("name")
            + "”. A backup is kept on this phone. Keep the app open until readback finishes.",
        () -> {
          requireBench();
          beforeChange();
          uploading = true;
          int token = ++transaction;
          List<byte[]> packets = Protocol.upload(p, adjustment);
          status("Writing calibration · keep app open");
          uploadNext(packets, 0, token, p);
        });
  }

  private void uploadNext(List<byte[]> packets, int index, int token, int[] proposed) {
    if (token != transaction || !link.isReady()) return;
    if (!link.isIdle()) {
      handler.postDelayed(() -> uploadNext(packets, index, token, proposed), 100);
      return;
    }
    if (index == packets.size()) {
      send("Commit calibration", Protocol.commit());
      uploading = false;
      expected = proposed.clone();
      lastTelemetry = 0;
      handler.postDelayed(this::safeRefresh, 1500);
      return;
    }
    send("Gear table " + (index + 1) + "/" + packets.size(), packets.get(index));
    handler.postDelayed(() -> uploadNext(packets, index + 1, token, proposed), 1100);
  }

  private void saveProfile() {
    try {
      JSONObject copy = new JSONObject(draft.toString());
      profiles.removeIf(p -> p.optString("name").equals(copy.optString("name")));
      profiles.add(copy);
      JSONArray a = new JSONArray();
      for (JSONObject p : profiles) a.put(p);
      prefs.edit().putString("profiles", a.toString()).apply();
      status("Profile saved on phone");
    } catch (JSONException e) {
      throw new IllegalArgumentException("Invalid profile");
    }
  }

  private static void put(JSONObject o, String k, Object v) {
    try {
      o.put(k, v);
    } catch (JSONException e) {
      throw new IllegalArgumentException(e);
    }
  }

  private static JSONArray array(int[] values) {
    JSONArray a = new JSONArray();
    for (int n : values) a.put(n);
    return a;
  }

  private static int[] fromArray(JSONArray a) {
    if (a == null) return new int[0];
    int[] p = new int[a.length()];
    for (int i = 0; i < p.length; i++)
      try {
        p[i] = a.getInt(i);
      } catch (JSONException e) {
        throw new IllegalArgumentException("Invalid position");
      }
    return p;
  }

  private static String mm(int n) {
    return String.format(Locale.ROOT, "%.3f", n / 600.0)
        .replaceAll("0+$", "")
        .replaceAll("\\.$", "");
  }

  private static int units(String s) {
    double value = Double.parseDouble(s.trim());
    if (!Double.isFinite(value) || value < 0 || value > 42)
      throw new IllegalArgumentException("Positions must be between 0 and 42 mm");
    return (int) Math.round(value * 600);
  }

  private static int[] parsePositions(String s) {
    if (s.trim().isEmpty()) return new int[0];
    String[] pieces = s.trim().split("[,;\\s]+");
    int[] p = new int[pieces.length];
    for (int i = 0; i < p.length; i++) p[i] = units(pieces[i]);
    return p;
  }

  private static String positionsText(JSONArray a) {
    int[] p = fromArray(a);
    StringJoiner s = new StringJoiner(", ");
    for (int n : p) s.add(mm(n));
    return s.toString();
  }

  private static String teethText(JSONArray a) {
    StringJoiner s = new StringJoiner(", ");
    for (int n : fromArray(a)) s.add(String.valueOf(n));
    return s.toString();
  }

  private void exportDraft() {
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "xshifter-profile.json"),
        42);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    try {
      Uri uri = data.getData();
      if (request == 42) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
          if (out == null) throw new IOException("Cannot open destination");
          out.write(draft.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        status("Profile exported");
      } else if (request == 41) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
          if (in == null) throw new IOException("Cannot read profile");
          ByteArrayOutputStream buffer = new ByteArrayOutputStream();
          byte[] chunk = new byte[4096];
          int n;
          while ((n = in.read(chunk)) != -1) {
            if (buffer.size() + n > 65536) throw new IOException("Profile is too large");
            buffer.write(chunk, 0, n);
          }
          JSONObject p = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
          validateProfile(p);
          draft = p;
          tab = 2;
          render();
        }
      }
    } catch (Exception e) {
      message("Profile operation failed", e.getMessage());
    }
  }

  private static void validateProfile(JSONObject p) {
    if (p.optInt("schema") != 1) throw new IllegalArgumentException("Unsupported profile format");
    int count = p.optInt("count");
    if (count < 2 || count > 17) throw new IllegalArgumentException("Invalid gear count");
    int[] positions = fromArray(p.optJSONArray("positions"));
    Catalog.validateTeeth(fromArray(p.optJSONArray("teeth")), count);
    if (positions.length > 0) {
      Protocol.validatePositions(positions);
      if (positions.length != count)
        throw new IllegalArgumentException("Gear count / position mismatch");
    }
    if (p.optInt("derailleur", -1) < 0
        || p.optInt("derailleur") >= Catalog.DERAILLEURS.size()
        || p.optInt("cassette", -1) < 0
        || p.optInt("cassette") >= Catalog.CASSETTES.size())
      throw new IllegalArgumentException("Unknown catalog selection");
  }

  private void startDemo() {
    if (connectionService != null) connectionService.disconnect();
    demo = true;
    bench = false;
    livePositions = Protocol.linear(11, 0, 18000);
    liveGear = 4;
    selectedGear = 4;
    adjustment = 0;
    demoBattery =
        new BatteryState.Reading(1200, SystemClock.elapsedRealtime(), System.currentTimeMillis());
    demoBatteryStep = 0;
    status("Demo · no hardware commands");
    render();
  }

  private void stopDemo() {
    if (demo) {
      demo = false;
      livePositions = null;
      liveGear = -1;
      demoBattery = null;
    }
  }

  private void confirm(String title, String message, Runnable yes) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Continue",
            (d, w) -> {
              try {
                yes.run();
              } catch (Exception e) {
                message("Could not complete action", e.getMessage());
              }
            })
        .show();
  }

  private void message(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message == null ? "Unknown error" : message)
        .setPositiveButton("OK", null)
        .show();
  }

  private void open(String url) {
    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    render();
  }

  @Override
  protected void onStop() {
    super.onStop();
    visible = false;
    handler.removeCallbacksAndMessages(null);
    transaction++;
    boolean keepMonitoring =
        !demo
            && connectionService != null
            && connectionService.monitoring()
            && authenticated
            && !uploading
            && expected == null
            && link != null
            && link.isIdle();
    if (!demo && !keepMonitoring && connectionService != null) connectionService.disconnect();
    if (link != null) link.stopScan();
    if (connectionService != null) connectionService.detach(this);
    awaitingTable = false;
    bench = false;
  }

  @Override
  protected void onStart() {
    super.onStart();
    visible = true;
    if (connectionService != null) restoreService();
    handler.removeCallbacks(batteryRefresh);
    handler.post(batteryRefresh);
  }

  private void restoreService() {
    connectionService.attach(this);
    if (!demo) {
      address = connectionService.address();
      identity = connectionService.identity();
      authenticated = connectionService.authenticated();
      bench = false;
      if (authenticated) safeRefresh();
      else if (livePositions != null) disconnected();
    }
    render();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (intent.getBooleanExtra("open_device", false)) {
      tab = 3;
      render();
    }
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    handler.removeCallbacksAndMessages(null);
    if (connectionService != null) connectionService.detach(this);
    if (serviceBound) unbindService(serviceConnection);
    super.onDestroy();
  }

  private final class TravelView extends View {
    private final int[] positions;
    private final int gear;
    private final Paint p = new Paint(3);

    TravelView(Context c, int[] values, int current) {
      super(c);
      positions = values == null ? null : values.clone();
      gear = current;
      setContentDescription("Gear positions across cable travel");
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float y = getHeight() / 2f;
      int n = positions == null ? 11 : positions.length;
      float left = dp(12), width = getWidth() - dp(24);
      p.setColor(0xff354453);
      p.setStrokeWidth(dp(2));
      c.drawLine(left, y, left + width, y, p);
      for (int i = 0; i < n; i++) {
        float x = left + width * i / Math.max(1, n - 1);
        p.setColor(i == gear ? ACCENT : 0xff566779);
        c.drawCircle(x, y, dp(i == gear ? 8 : 4), p);
      }
    }
  }
}
