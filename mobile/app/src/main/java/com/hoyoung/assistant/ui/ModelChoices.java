package com.hoyoung.assistant.ui;

/** The three models offered by the owner-facing selectors. */
public final class ModelChoices {
    private static final String[] IDS = {"gpt-6-sol", "gpt-6.1-sol", "gpt-6-astra"};
    private static final String[] LABELS = {"6 Sol", "6.1 Sol", "Astra"};
    private ModelChoices() {}
    public static String[] labels() { return LABELS.clone(); }
    public static String id(int position) { return IDS[position]; }
    public static int index(String id) {
        for (int i=0;i<IDS.length;i++) if (IDS[i].equals(id)) return i;
        return 1;
    }
    public static String label(String id) {
        for (int i=0;i<IDS.length;i++) if (IDS[i].equals(id)) return LABELS[i];
        return id; // Do not mislabel a historical selection from an older backup.
    }
}
