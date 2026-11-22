package com.thor.refreshrate;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.text.InputType;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.Locale;

/** Minimal dashboard. Privileged permission setup runs only on explicit user action. */
public final class MainActivity extends Activity {
    private static final int EXPORT_SETTINGS = 4701;
    private static final int IMPORT_SETTINGS = 4702;
    private static final int BG = Color.rgb(16, 20, 27);
    private static final int CARD = Color.rgb(28, 35, 45);
    private static final int MUTED = Color.rgb(153, 167, 184);
    private static final int ACCENT = Color.rgb(133, 187, 255);
    private static final int SUCCESS = Color.rgb(87, 207, 145);
    private static final int WHITE = Color.rgb(242, 246, 251);
    private static final int DIVIDER = Color.rgb(49, 60, 73);
    private static final int DANGER = Color.rgb(240, 92, 107);

    private ProfileStore profiles;
    private AppUpdater updater;
    private LinearLayout content;
    private ScrollView scroll;
    private boolean showManualPermissionFallback;
    private String permissionSetupResult;
    private Boolean permissionsExpanded;
    private boolean previousPermissionsComplete;
    private final Set<String> expandedGames = new HashSet<>();
    private final Set<String> collapsedCategories = new HashSet<>();
    private boolean allAppsExpanded;
    private final Map<String, View> profileViews = new HashMap<>();
    private final Set<String> queuedTitles = new HashSet<>();
    private ExecutorService titleExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        profiles = new ProfileStore(this);
        updater = new AppUpdater(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(28), dp(18), dp(28));
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        draw(false);
        updater.onResume();
    }


    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            if (requestCode == EXPORT_SETTINGS) {
                SettingsBackup.exportTo(this, data.getData());
                Toast.makeText(this, "Settings backup saved", Toast.LENGTH_LONG).show();
            } else if (requestCode == IMPORT_SETTINGS) {
                new AlertDialog.Builder(this)
                        .setTitle("Restore settings?")
                        .setMessage("Replace this installation's saved profiles, categories, "
                                + "rates and switches with those from the selected backup?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Restore", (dialog, which) -> {
                            try {
                                SettingsBackup.restoreFrom(this, data.getData());
                                profiles = new ProfileStore(this);
                                draw(true);
                                Toast.makeText(this, "Settings restored", Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                new AlertDialog.Builder(this).setTitle("Restore failed")
                                        .setMessage(e.getMessage()).setPositiveButton("OK", null)
                                        .show();
                            }
                        }).show();
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Backup failed")
                    .setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    private void draw(boolean keepScroll) {
        int previousScroll = keepScroll ? scroll.getScrollY() : 0;
        content.removeAllViews();
        profileViews.clear();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(14));
        TextView title = text(getString(R.string.app_name), 22, WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch power = new Switch(this);
        power.setContentDescription("Automatic FPS switching");
        power.setText(profiles.enabled() ? "On" : "Off");
        power.setTextColor(profiles.enabled() ? SUCCESS : MUTED);
        power.setTextSize(14);
        power.setChecked(profiles.enabled());
        power.setOnCheckedChangeListener((button, enabled) -> {
            profiles.setEnabled(enabled);
            draw(true);
        });
        TextView update = text("↻", 25, ACCENT, false);
        update.setGravity(Gravity.CENTER);
        update.setMinWidth(dp(44));
        update.setMinHeight(dp(44));
        update.setContentDescription("Check for updates");
        update.setOnClickListener(v -> updater.checkForUpdates());
        update.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, update);
            menu.getMenu().add("Backup settings");
            menu.getMenu().add("Restore settings");
            menu.setOnMenuItemClickListener(item -> {
                if ("Backup settings".contentEquals(item.getTitle())) {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.setType("application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_TITLE, "ThorRefreshRateControl-backup.json");
                    startActivityForResult(intent, EXPORT_SETTINGS);
                } else {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.setType("application/json");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, IMPORT_SETTINGS);
                }
                return true;
            });
            menu.show();
            return true;
        });
        header.addView(update);
        header.addView(power);
        content.addView(header);

        List<Profile> observed = profiles.all();
        drawPermissions();
        List<Profile> recent = AppCatalog.recent(observed, profiles.recentApps());
        if (!recent.isEmpty()) {
            section("RECENTLY USED");
            LinearLayout shortcuts = card();
            if (twoColumns()) {
                for (int i = 0; i < recent.size(); i += 2) {
                    LinearLayout row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (int j = 0; j < 2; j++) {
                        LinearLayout cell = new LinearLayout(this);
                        cell.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        if (j == 1) lp.leftMargin = dp(16);
                        row.addView(cell, lp);
                        if (i + j < recent.size()) addRecentShortcut(cell, recent.get(i + j));
                    }
                    shortcuts.addView(row);
                }
            } else {
                for (Profile profile : recent) addRecentShortcut(shortcuts, profile);
            }
            content.addView(shortcuts);
        }

        // All apps is a complete view; categories are optional, additional views.
        List<String> categories = profiles.categories();
        List<Profile> apps = new ArrayList<>();
        Map<String, List<Profile>> gameGroups = new HashMap<>();
        for (Profile profile : observed) {
            if (profile.game) {
                gameGroups.computeIfAbsent(profile.packageName, ignored -> new ArrayList<>())
                        .add(profile);
            } else {
                apps.add(profile);
            }
        }
        apps.sort(Comparator.comparing((Profile p) -> p.name,
                String.CASE_INSENSITIVE_ORDER).thenComparing(p -> p.packageName));
        for (List<Profile> games : gameGroups.values())
            games.sort(Comparator.comparing((Profile p) -> GameTitles.display(p.name),
                    String.CASE_INSENSITIVE_ORDER).thenComparing(p -> p.id));

        drawAppGroup("All apps", "", apps, gameGroups);
        for (String category : categories) {
            List<Profile> assigned = new ArrayList<>();
            for (Profile profile : apps) {
                if (category.equals(profiles.categoryFor(profile.packageName)))
                    assigned.add(profile);
            }
            drawAppGroup(category, category, assigned, gameGroups);
        }
        if (keepScroll) scroll.post(() -> scroll.scrollTo(0, previousScroll));
    }

    /** Recent apps are shortcuts, not duplicate profile editors. */
    private void addRecentShortcut(LinearLayout container, Profile profile) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = appIcon(profile.packageName);
        if (icon.getDrawable() != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(27), dp(27));
            ip.rightMargin = dp(12);
            item.addView(icon, ip);
        }
        TextView title = text(profile.name, 14, WHITE, false);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(text("›", 20, MUTED, false));
        container.addView(item);
        item.setOnClickListener(v -> {
            allAppsExpanded = true;
            draw(false);
            View target = profileViews.get(profile.id);
            if (target != null) scroll.post(() -> scroll.smoothScrollTo(0,
                    descendantTop(target)));
        });
    }

    private void drawAppGroup(String name, String category, List<Profile> apps,
                              Map<String, List<Profile>> games) {
        boolean allApps = category.isEmpty();
        boolean expanded = allApps ? allAppsExpanded : !collapsedCategories.contains(category);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(2), dp(9), dp(2), dp(2));

        TextView arrow = text(expanded ? "▾" : "▸", 17, MUTED, false);
        arrow.setGravity(Gravity.CENTER_VERTICAL);
        arrow.setMinWidth(dp(28));
        arrow.setMinHeight(dp(36));
        arrow.setContentDescription((expanded ? "Collapse " : "Expand ") + name);
        arrow.setOnClickListener(v -> toggleGroup(category));
        heading.addView(arrow);

        TextView title = text(name.toUpperCase(Locale.ROOT), 12, WHITE, true);
        title.setLetterSpacing(0.09f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(36));
        title.setOnClickListener(v -> {
            if (allApps) toggleGroup(category);
            else showRenameMenu(category, title);
        });
        title.setContentDescription(allApps ? "Expand or collapse All apps" : "Rename " + name);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (allApps) {
            TextView add = text("+", 22, ACCENT, false);
            add.setGravity(Gravity.CENTER);
            add.setMinWidth(dp(40));
            add.setMinHeight(dp(40));
            add.setContentDescription("Add category");
            add.setOnClickListener(v -> editCategory(null, null));
            heading.addView(add);
        } else {
            ImageButton delete = new ImageButton(this);
            delete.setImageResource(android.R.drawable.ic_menu_delete);
            delete.setColorFilter(DANGER);
            delete.setBackgroundColor(Color.TRANSPARENT);
            delete.setPadding(dp(10), dp(10), dp(10), dp(10));
            delete.setMinimumWidth(dp(40));
            delete.setMinimumHeight(dp(40));
            delete.setContentDescription("Delete category " + name);
            delete.setOnClickListener(v -> confirmRemoveCategory(category));
            heading.addView(delete, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        content.addView(heading);
        if (!expanded) return;

        if (apps.isEmpty()) {
            TextView empty = mutedLine(allApps
                    ? "Apps appear after they have been opened."
                    : "Tap + on an app in All apps to add it here.");
            empty.setPadding(dp(28), dp(6), 0, dp(9));
            content.addView(empty);
            return;
        }
        if (twoColumns()) {
            for (int i = 0; i < apps.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int column = 0; column < 2; column++) {
                    int index = i + column;
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (column == 1) cp.leftMargin = dp(10);
                    if (index < apps.size()) {
                        Profile profile = apps.get(index);
                        row.addView(buildProfile(profile, games.getOrDefault(
                                profile.packageName, Collections.emptyList())), cp);
                    } else row.addView(new View(this), cp);
                }
                content.addView(row);
            }
        } else {
            for (Profile profile : apps)
                content.addView(buildProfile(profile, games.getOrDefault(
                        profile.packageName, Collections.emptyList())));
        }
    }

    private void toggleGroup(String category) {
        if (category.isEmpty()) allAppsExpanded = !allAppsExpanded;
        else if (!collapsedCategories.add(category)) collapsedCategories.remove(category);
        draw(true);
    }

    private void showRenameMenu(String category, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Rename");
        menu.setOnMenuItemClickListener(item -> {
            editCategory(category, null);
            return true;
        });
        menu.show();
    }

    /** Creating and renaming categories both modify the original persisted store. */
    private void editCategory(String current, Profile assign) {
        boolean rename = current != null;
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Category name");
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setFilters(new android.text.InputFilter[] {
                new android.text.InputFilter.LengthFilter(32) });
        if (rename) {
            field.setText(current);
            field.setSelection(current.length());
        }
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(16), 0, dp(16), 0);
        wrapper.addView(field);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(rename ? "Rename category" : "New category")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(rename ? "Save" : "Add", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String next = field.getText().toString().trim();
                    boolean saved = rename ? profiles.renameCategory(current, next)
                            : profiles.addCategory(next);
                    if (!saved) {
                        field.setError("Use a unique name (1–32 characters; not All apps)");
                        return;
                    }
                    if (rename && collapsedCategories.remove(current)) collapsedCategories.add(next);
                    if (assign != null) profiles.setCategory(assign.packageName, next);
                    dialog.dismiss();
                    draw(true);
                }));
        dialog.show();
    }

    private void confirmRemoveCategory(String category) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + category + "?")
                .setMessage("The category will be deleted. Its apps remain in All apps, "
                        + "and their refresh-rate profiles will not change.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    profiles.removeCategory(category);
                    collapsedCategories.remove(category);
                    draw(true);
                }).show();
    }

    private void showCategoryMenu(Profile profile, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String current = profiles.categoryFor(profile.packageName);
        List<String> categories = profiles.categories();
        if (!current.isEmpty()) menu.getMenu().add(0, 0, 0, "Remove from category");
        for (int i = 0; i < categories.size(); i++) {
            menu.getMenu().add(0, i + 1, i + 1, categories.get(i))
                    .setChecked(categories.get(i).equals(current));
        }
        menu.getMenu().add(0, categories.size() + 1, categories.size() + 1,
                "+ New category");
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == categories.size() + 1) {
                editCategory(null, profile);
            } else {
                profiles.setCategory(profile.packageName,
                        id == 0 ? "" : categories.get(id - 1));
                draw(true);
            }
            return true;
        });
        menu.show();
    }

    private boolean twoColumns() {
        return getResources().getConfiguration().screenWidthDp >= 640;
    }

    private int descendantTop(View descendant) {
        int top = 0;
        View node = descendant;
        while (node != content) {
            top += node.getTop();
            ViewParent parent = node.getParent();
            if (!(parent instanceof View)) break;
            node = (View) parent;
        }
        return top;
    }

    private View buildProfile(Profile profile, List<Profile> games) {
        LinearLayout panel = card();
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        ImageView icon = appIcon(profile.packageName);
        if (icon.getDrawable() != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(38), dp(38));
            ip.setMargins(0, 0, dp(12), 0);
            top.addView(icon, ip);
        }
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(profile.name, 16, WHITE, true);
        name.setMaxLines(2);
        labels.addView(name);
        top.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String category = profiles.categoryFor(profile.packageName);
        TextView selector = text(category.isEmpty() ? "+" : category + " ▾",
                category.isEmpty() ? 22 : 12, ACCENT, true);
        selector.setMaxLines(1);
        selector.setEllipsize(android.text.TextUtils.TruncateAt.END);
        selector.setGravity(Gravity.CENTER);
        selector.setPadding(dp(7), 0, dp(7), 0);
        selector.setMinHeight(dp(40));
        selector.setMinWidth(dp(40));
        selector.setMaxWidth(dp(110));
        if (!category.isEmpty()) selector.setBackground(rounded(DIVIDER, 9, 0));
        selector.setContentDescription((category.isEmpty() ? "Add " : "Change ")
                + "category for " + profile.name);
        selector.setOnClickListener(v -> showCategoryMenu(profile, selector));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.leftMargin = dp(8);
        top.addView(selector, sp);
        panel.addView(top);

        boolean droidDeck = ActivityModeDetector.isDroidDeck(profile.packageName);
        boolean hasSession = ProfileStore.hasSessionProfile(profile.packageName);
        if (hasSession) addCaption(panel, droidDeck ? "FRONTEND / STEAM MENU" : "MENU / LIBRARY");
        addRateChoices(panel, profile.id, profile.rate, "Manual");

        if (hasSession) {
            addCaption(panel, droidDeck ? "STEAM GAMEPLAY (DEFAULT)" : "GAMEPLAY (ALL GAMES)");
            String gameplay = ProfileStore.gameplayId(profile.packageName);
            addRateChoices(panel, gameplay, profiles.rate(gameplay), "Menu rate");
        }

        int gameCount = games.size();
        if (gameCount > 0) {
            final String pkg = profile.packageName;
            boolean open = expandedGames.contains(pkg);
            TextView gameToggle = text((open ? "▾  " : "▸  ") + "Individual games (" + gameCount + ")",
                    14, ACCENT, true);
            gameToggle.setPadding(0, dp(16), 0, dp(8));
            gameToggle.setOnClickListener(v -> {
                if (!expandedGames.add(pkg)) expandedGames.remove(pkg);
                draw(true);
            });
            panel.addView(gameToggle);
            if (open) {
                for (Profile child : games) {
                    TextView gameName = text(GameTitles.display(child.name), 13, MUTED, true);
                    gameName.setPadding(0, dp(14), 0, 0);
                    panel.addView(gameName);
                    if (GameTitles.unresolved(child.name)) {
                        // Name lookups are strictly opt-in; no automatic network or library access.
                        gameName.setOnClickListener(v -> renameGame(child));
                        if (isSteamGame(child)) {
                            Button find = action("Find name");
                            find.setOnClickListener(v -> lookupTitle(child, find));
                            panel.addView(find);
                        }
                    }
                    addRateChoices(panel, child.id, child.rate,
                            hasSession ? "Gameplay default" : "App default");
                }
            }
        }
        View wrapper = margin(panel, 9);
        // Keep the All apps card as the shortcut target when a second copy exists.
        if (!profileViews.containsKey(profile.id)) profileViews.put(profile.id, wrapper);
        return wrapper;
    }

    private boolean isSteamGame(Profile child) {
        return ActivityModeDetector.isDroidDeck(child.packageName)
                && child.id.startsWith("game:" + child.packageName + ":steam-");
    }

    /** Explicitly requested by the user: local library, then Steam fallback. */
    private void lookupTitle(Profile child, Button button) {
        if (!isSteamGame(child) || !queuedTitles.add(child.id)) return;
        button.setEnabled(false);
        button.setText("Finding...");
        if (titleExecutor == null) titleExecutor = Executors.newSingleThreadExecutor();
        String prefix = "game:" + child.packageName + ":steam-";
        String steamId = child.id.substring(prefix.length());
        titleExecutor.execute(() -> {
            String resolved = SteamTitleResolver.resolve(getApplicationContext(), steamId);
            if (resolved != null) profiles.updateGameName(child.id, resolved);
            runOnUiThread(() -> {
                queuedTitles.remove(child.id);
                if (isFinishing() || isDestroyed()) return;
                if (resolved == null) {
                    button.setEnabled(true);
                    button.setText("Find name");
                    Toast.makeText(this, "Name not found", Toast.LENGTH_SHORT).show();
                } else draw(true);
            });
        });
    }

    private void renameGame(Profile child) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("Game name");
        int inset = dp(16);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(inset, 0, inset, 0);
        wrapper.addView(field);
        new AlertDialog.Builder(this)
                .setTitle("Game name")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    profiles.renameGame(child.id, field.getText().toString());
                    draw(true);
                }).show();
    }

    @Override protected void onDestroy() {
        if (titleExecutor != null) titleExecutor.shutdownNow();
        super.onDestroy();
    }

    /** Always-visible generic permissions; collapse only when all three are granted. */
    private void drawPermissions() {
        boolean write = Settings.System.canWrite(this);
        boolean monitoring = isMonitoringEnabled();
        boolean gameAccess = LocalAdbGrant.granted(this);
        boolean complete = write && monitoring && gameAccess;
        if (!complete) permissionsExpanded = true;
        else if (permissionsExpanded == null || !previousPermissionsComplete)
            permissionsExpanded = false;
        previousPermissionsComplete = complete;

        LinearLayout panel = card();
        TextView heading = text((complete ? "✓  Permissions granted" : "Permissions")
                + (permissionsExpanded ? "   ▾" : "   ▸"), 16,
                complete ? SUCCESS : WHITE, true);
        heading.setPadding(0, dp(2), 0, permissionsExpanded ? dp(9) : dp(2));
        heading.setOnClickListener(v -> {
            if (complete) {
                permissionsExpanded = !permissionsExpanded;
                draw(true);
            }
        });
        panel.addView(heading);
        if (permissionsExpanded) {
            permissionRow(panel, "Modify system settings", write,
                    "Allow", () -> startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + getPackageName()))));
            permissionRow(panel, "App detection", monitoring,
                    "Enable", () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            permissionRow(panel, "Game detection", gameAccess, null, null);
            if (!gameAccess) addGameDetectionSetup(panel);
        }
        content.addView(margin(panel, 0));
    }

    private TextView mutedLine(String text) {
        TextView line = text(text, 13, MUTED, false);
        line.setPadding(0, dp(9), 0, dp(6));
        return line;
    }

    private void permissionRow(LinearLayout panel, String label, boolean granted,
                               String actionLabel, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text((granted ? "✓  " : "○  ") + label, 13,
                granted ? SUCCESS : WHITE, false);
        name.setPadding(0, dp(8), 0, dp(8));
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!granted && actionLabel != null && action != null) {
            Button button = action(actionLabel);
            button.setOnClickListener(v -> action.run());
            row.addView(button);
        }
        panel.addView(row);
    }

    private void addGameDetectionSetup(LinearLayout panel) {
        Button enable = action("Enable game detection");
        TextView result = text(permissionSetupResult == null ? "" : permissionSetupResult,
                12, MUTED, false);
        panel.addView(enable);
        panel.addView(result);
        enable.setOnClickListener(v -> {
            enable.setEnabled(false);
            new Thread(() -> {
                RateController.grantOwnDumpPermission();
                boolean granted = LocalAdbGrant.granted(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    enable.setEnabled(true);
                    if (granted) {
                        showManualPermissionFallback = false;
                        permissionSetupResult = null;
                    } else {
                        showManualPermissionFallback = true;
                        permissionSetupResult = "Automatic setup unavailable on this firmware.";
                    }
                    draw(true);
                });
            }, "ThorPermissionGrant").start();
        });
        if (showManualPermissionFallback) {
            panel.addView(mutedLine("Alternative: enable Wireless debugging and pair locally."));
            Button settings = action("Open Wireless debugging settings");
            settings.setOnClickListener(v -> startActivity(
                    new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
            panel.addView(settings);
            EditText pairingPort = input("Pairing port", false);
            EditText pairingCode = input("Six-digit pairing code", true);
            EditText connectionPort = input("Connection port (optional)", false);
            panel.addView(pairingPort);
            panel.addView(pairingCode);
            panel.addView(connectionPort);
            TextView fallbackResult = text("", 12, MUTED, false);
            panel.addView(fallbackResult);
            Button pair = action("Pair on this Thor");
            pair.setOnClickListener(v -> {
                pair.setEnabled(false);
                LocalAdbGrant.pairAndGrant(this, pairingPort.getText().toString(),
                        pairingCode.getText().toString(), connectionPort.getText().toString(),
                        (message, granted) -> runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            pair.setEnabled(true);
                            fallbackResult.setText(message);
                            if (granted && LocalAdbGrant.granted(this)) {
                                showManualPermissionFallback = false;
                                permissionSetupResult = null;
                                draw(true);
                            }
                        }));
            });
            panel.addView(pair);
        }
    }

    private EditText input(String hint, boolean code) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint(hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(WHITE);
        field.setTextSize(14);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | (code
                ? InputType.TYPE_NUMBER_VARIATION_PASSWORD : 0));
        field.setPadding(dp(4), dp(10), dp(4), dp(10));
        return field;
    }

    private void addCaption(LinearLayout panel, String label) {
        TextView caption = text(label, 11, MUTED, true);
        caption.setPadding(0, dp(14), 0, 0);
        panel.addView(caption);
    }

    private void addRateChoices(LinearLayout panel, String id, int currentRate, String inheritLabel) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(8), 0, 0);
        String[] labels = { inheritLabel, "60 Hz", "120 Hz" };
        int[] values = {0, 60, 120};
        for (int i = 0; i < values.length; i++) {
            int hz = values[i];
            TextView choice = text(labels[i], 12, currentRate == hz ? BG : WHITE, true);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(4), dp(12), dp(4), dp(12));
            choice.setBackground(rounded(currentRate == hz ? ACCENT : DIVIDER, 9, 0));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i != 0) cp.leftMargin = dp(6);
            controls.addView(choice, cp);
            choice.setOnClickListener(v -> {
                profiles.setRate(id, hz);
                if (hz != 0 && !Settings.System.canWrite(this))
                    Toast.makeText(this, "First grant Modify system settings", Toast.LENGTH_LONG).show();
                draw(true);
            });
        }
        panel.addView(controls);
    }

    private boolean isMonitoringEnabled() {
        AccessibilityManager manager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        ComponentName required = new ComponentName(this, AppMonitorService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null) {
                ComponentName actual = new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name);
                if (actual.equals(required)) return true;
            }
        }
        return false;
    }

    private ImageView appIcon(String packageName) {
        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(packageName));
        } catch (PackageManager.NameNotFoundException ignored) { }
        return icon;
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private void section(String label) {
        TextView v = text(label, 11, MUTED, true);
        v.setLetterSpacing(0.12f);
        v.setPadding(dp(3), dp(20), 0, dp(10));
        content.addView(v);
    }

    private LinearLayout card() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(rounded(CARD, 15, DIVIDER));
        return panel;
    }

    private View margin(View view, int top) {
        LinearLayout wrapper = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        wrapper.addView(view, lp);
        return wrapper;
    }

    private Button action(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(BG);
        button.setBackground(rounded(ACCENT, 10, 0));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        return button;
    }

    private GradientDrawable rounded(int fill, int radius, int border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radius));
        if (border != 0) bg.setStroke(dp(1), border);
        return bg;
    }
}
