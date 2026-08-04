package com.amaral.driverlab;

/** Brand and functional colors extracted from the official Amaral app identity. */
final class AmaralColors {
    static final int BACKGROUND = 0xff060814;
    static final int SURFACE = 0xff101427;
    static final int SURFACE_ELEVATED = 0xff181d35;
    static final int SURFACE_HIGHLIGHT = 0xff212746;
    static final int BRAND_PRIMARY = 0xff8b4cff;
    static final int BRAND_PRIMARY_DARK = 0xff5b2db5;
    static final int BRAND_SECONDARY = 0xff00b8ff;
    static final int BRAND_SECONDARY_DARK = 0xff005fa6;
    static final int TEXT_PRIMARY = 0xfff6f7ff;
    static final int TEXT_SECONDARY = 0xffb7bed6;
    static final int TEXT_MUTED = 0xff848dab;
    static final int BORDER = 0xff363f63;
    static final int SUCCESS = 0xff35d399;
    static final int WARNING = 0xfffbbf24;
    static final int ERROR = 0xfff87171;
    static final int INFO = 0xff38bdf8;
    static final int UNAVAILABLE = 0xff94a3b8;

    private AmaralColors() {}

    static double contrastRatio(int foreground, int background) {
        double light = luminance(foreground);
        double dark = luminance(background);
        if (light < dark) {
            double swap = light;
            light = dark;
            dark = swap;
        }
        return (light + 0.05d) / (dark + 0.05d);
    }

    private static double luminance(int color) {
        return 0.2126d * channel((color >> 16) & 0xff)
                + 0.7152d * channel((color >> 8) & 0xff)
                + 0.0722d * channel(color & 0xff);
    }

    private static double channel(int value) {
        double normalized = value / 255.0d;
        return normalized <= 0.03928d
                ? normalized / 12.92d
                : Math.pow((normalized + 0.055d) / 1.055d, 2.4d);
    }
}
