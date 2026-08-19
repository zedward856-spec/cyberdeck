// cyberterm - GTK3 + VTE. Rounded tabs, squircle + button after the last tab,
// distinct tab-bar bg, directory shown in each tab (left-ellipsized).
using Gtk;
using Vte;

public class CyberTerm : Gtk.Window {
    private Gtk.Notebook notebook;
    private Gtk.Widget plus_page;
    private bool making = false;

    private const string[] PALETTE = {
        "#141820", "#C9342F", "#5CC48D", "#E0A445",
        "#367BF0", "#8A6EF0", "#5FD7FF", "#C5D1E6",
        "#3D5468", "#F0736C", "#7EE7A8", "#F5C15B",
        "#5B96F5", "#B296FF", "#7DE7FF", "#EAF2FF"
    };

    public CyberTerm() {
        this.title = "cyberterm";
        this.set_default_size(940, 580);
        this.icon_name = "utilities-terminal";
        this.maximize();

        notebook = new Gtk.Notebook();
        notebook.scrollable = true;
        notebook.show_border = false;
        notebook.switch_page.connect((page, num) => {
            if (page == plus_page && !making && notebook.get_n_pages() > 1) {
                Idle.add(() => { new_tab(); return false; });
            }
        });

        this.add(notebook);
        this.key_press_event.connect(on_key);
        this.destroy.connect(Gtk.main_quit);

        build_plus_tab();
        new_tab();
    }

    private void build_plus_tab() {
        plus_page = new Gtk.Box(Gtk.Orientation.VERTICAL, 0);
        var plus_btn = new Gtk.Button.from_icon_name("list-add-symbolic", Gtk.IconSize.MENU);
        plus_btn.relief = Gtk.ReliefStyle.NONE;
        plus_btn.tooltip_text = "New tab (Ctrl+Shift+T)";
        plus_btn.get_style_context().add_class("plusbtn");
        plus_btn.clicked.connect(() => { new_tab(); });
        plus_btn.show();
        notebook.append_page(plus_page, plus_btn);
        notebook.set_tab_reorderable(plus_page, false);
    }

    private void new_tab() {
        making = true;
        var term = new Vte.Terminal();

        var bg = Gdk.RGBA();  bg.parse("#0D0F14");
        var fg = Gdk.RGBA();  fg.parse("#C5D1E6");
        Gdk.RGBA[] pal = new Gdk.RGBA[16];
        for (int i = 0; i < 16; i++) { pal[i] = Gdk.RGBA(); pal[i].parse(PALETTE[i]); }
        term.set_colors(fg, bg, pal);
        term.set_font(Pango.FontDescription.from_string("Share Tech Mono 14"));
        term.set_scrollback_lines(10000);
        term.set_cursor_shape(Vte.CursorShape.BLOCK);
        term.set_cursor_blink_mode(Vte.CursorBlinkMode.ON);
        term.set_mouse_autohide(true);

        string shell = Environment.get_variable("SHELL");
        if (shell == null) shell = "/bin/zsh";
        string[] argv = { shell };
        term.spawn_async(Vte.PtyFlags.DEFAULT, Environment.get_home_dir(),
                         argv, null, GLib.SpawnFlags.SEARCH_PATH, null, -1, null, null);

        var label = new Gtk.Label("shell");
        label.ellipsize = Pango.EllipsizeMode.START;    // show the RIGHT end (current dir)
        label.max_width_chars = 24;
        label.xalign = 0.0f;
        var close_btn = new Gtk.Button.from_icon_name("window-close-symbolic", Gtk.IconSize.MENU);
        close_btn.relief = Gtk.ReliefStyle.NONE;
        close_btn.get_style_context().add_class("tabclose");
        var header = new Gtk.Box(Gtk.Orientation.HORIZONTAL, 4);
        header.get_style_context().add_class("tabheader");
        header.pack_start(label, true, true, 0);
        header.pack_start(close_btn, false, false, 0);
        header.show_all();

        term.show();
        int pos = notebook.page_num(plus_page);
        if (pos < 0) pos = notebook.get_n_pages();
        notebook.insert_page(term, header, pos);
        notebook.set_tab_reorderable(term, true);
        notebook.set_current_page(pos);

        close_btn.clicked.connect(() => { close_term(term); });
        term.child_exited.connect(() => { close_term(term); });
        term.notify["current-directory-uri"].connect(() => { update_label(term, label); });
        term.notify["window-title"].connect(() => { update_label(term, label); });

        term.grab_focus();
        making = false;
    }

    private void update_label(Vte.Terminal term, Gtk.Label label) {
        string dir = term.current_directory_uri;
        if (dir != null && dir != "") {
            try {
                string path = GLib.Filename.from_uri(dir, null);
                string home = Environment.get_home_dir();
                if (home != null && path.has_prefix(home))
                    path = "~" + path.substring(home.length);
                label.label = path;
                return;
            } catch (Error e) { /* fall through */ }
        }
        var t = term.window_title;
        label.label = (t != null && t != "") ? t : "shell";
    }

    private void close_term(Vte.Terminal term) {
        int n = notebook.page_num(term);
        if (n >= 0) notebook.remove_page(n);
        if (notebook.get_n_pages() <= 1) Gtk.main_quit();
    }

    private Vte.Terminal? current_term() {
        int p = notebook.get_current_page();
        if (p < 0) return null;
        return notebook.get_nth_page(p) as Vte.Terminal;
    }

    private bool on_key(Gdk.EventKey e) {
        var mods = e.state & (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.SHIFT_MASK);
        if (mods == (Gdk.ModifierType.CONTROL_MASK | Gdk.ModifierType.SHIFT_MASK)) {
            switch (e.keyval) {
                case Gdk.Key.T: new_tab(); return true;
                case Gdk.Key.W: var t = current_term(); if (t != null) close_term(t); return true;
                case Gdk.Key.C: var c = current_term(); if (c != null) c.copy_clipboard_format(Vte.Format.TEXT); return true;
                case Gdk.Key.V: var v = current_term(); if (v != null) v.paste_clipboard(); return true;
            }
        }
        return false;
    }

    private static void load_css() {
        var css = new Gtk.CssProvider();
        string style = "\n" +
        "window { background-color: #141820; }\n" +
        "notebook > stack { background-color: #0D0F14; }\n" +
        "notebook > header {\n" +
        "  background-color: #141820;\n" +
        "  border: none;\n" +
        "  border-bottom: 1px solid #05070A;\n" +
        "  padding: 4px 6px 0 6px;\n" +
        "}\n" +
        "notebook > header > tabs > tab {\n" +
        "  background-color: #1C212C;\n" +
        "  color: #7D8598;\n" +
        "  border: 1px solid #2A2F3B;\n" +
        "  border-bottom: none;\n" +
        "  border-radius: 10px 10px 0 0;\n" +
        "  margin: 0 3px 0 0;\n" +
        "  padding: 3px 10px;\n" +
        "  min-height: 0;\n" +
        "}\n" +
        "notebook > header > tabs > tab:checked {\n" +
        "  background-color: #24344F;\n" +
        "  color: #5FD7FF;\n" +
        "  border-color: #367BF0;\n" +
        "}\n" +
        "notebook > header > tabs > tab:hover { background-color: #23252E; }\n" +
        ".tabheader { min-width: 150px; }\n" +
        "notebook tab label { color: inherit; }\n" +
        "notebook > header > tabs > tab:last-child {\n" +
        "  background: transparent;\n" +
        "  border: none;\n" +
        "  padding: 0;\n" +
        "  margin: 0 0 0 2px;\n" +
        "  min-width: 0;\n" +
        "}\n" +
        "button.plusbtn {\n" +
        "  color: #5FD7FF;\n" +
        "  background-color: #1C212C;\n" +
        "  border: 1px solid #2A2F3B;\n" +
        "  border-radius: 10px;\n" +
        "  padding: 5px 8px;\n" +
        "  margin: 0;\n" +
        "  min-height: 0;\n" +
        "  min-width: 0;\n" +
        "}\n" +
        "button.plusbtn:hover { background-color: #24344F; border-color: #367BF0; }\n" +
        "button { border-radius: 8px; padding: 2px 6px; }\n" +
        "button.tabclose { padding: 0 2px; margin: 0; min-height: 0; min-width: 0; }\n" +
        "button:hover { background-color: #23252E; }\n";
        try {
            css.load_from_data(style);
            Gtk.StyleContext.add_provider_for_screen(
                Gdk.Screen.get_default(), css, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        } catch (Error e) { warning("css: %s", e.message); }
    }

    public static int main(string[] args) {
        Gtk.init(ref args);
        load_css();
        var win = new CyberTerm();
        win.show_all();
        Gtk.main();
        return 0;
    }
}
