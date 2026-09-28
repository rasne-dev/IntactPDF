import os
import math
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "..", "store-assets")
os.makedirs(OUTPUT_DIR, exist_ok=True)

FONTS_DIR = os.path.join(os.environ.get("WINDIR", "C:\\Windows"), "Fonts")

def get_font(size, bold=False):
    filename = "segoeuib.ttf" if bold else "segoeui.ttf"
    path = os.path.join(FONTS_DIR, filename)
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()

# -------------------------------------------------------------
# 1. APP ICON (512x512)
# -------------------------------------------------------------
def generate_app_icon():
    size = (512, 512)
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded background with smooth gradient
    bg = Image.new("RGBA", size, (0, 0, 0, 0))
    bg_draw = ImageDraw.Draw(bg)
    # Background squircle
    bg_draw.rounded_rectangle([24, 24, 488, 488], radius=110, fill=(24, 119, 242, 255))
    
    # Inner subtle gradient / overlay
    grad = Image.new("RGBA", size, (0, 0, 0, 0))
    grad_draw = ImageDraw.Draw(grad)
    for y in range(size[1]):
        alpha = int(40 * (y / size[1]))
        grad_draw.line([(0, y), (size[0], y)], fill=(10, 40, 90, alpha))
    bg = Image.alpha_composite(bg, grad)

    # Document shape in center
    # Document dimensions: 220 wide x 300 tall, centered
    doc_x0, doc_y0 = 146, 106
    doc_x1, doc_y1 = 366, 406
    fold = 60

    # Document shadow
    shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    s_draw = ImageDraw.Draw(shadow)
    s_draw.rounded_rectangle([doc_x0-6, doc_y0+8, doc_x1+6, doc_y1+14], radius=18, fill=(0, 0, 0, 80))
    shadow = shadow.filter(ImageFilter.GaussianBlur(10))
    bg = Image.alpha_composite(bg, shadow)

    d_draw = ImageDraw.Draw(bg)
    # Document polygon with folded corner at top-right
    points = [
        (doc_x0 + 16, doc_y0),
        (doc_x1 - fold, doc_y0),
        (doc_x1, doc_y0 + fold),
        (doc_x1, doc_y1 - 16),
        (doc_x1 - 16, doc_y1),
        (doc_x0 + 16, doc_y1),
        (doc_x0, doc_y1 - 16),
        (doc_x0, doc_y0 + 16)
    ]
    d_draw.polygon(points, fill=(255, 255, 255, 255))
    d_draw.rounded_rectangle([doc_x0, doc_y0 + fold, doc_x1, doc_y1], radius=16, fill=(255, 255, 255, 255))
    d_draw.rounded_rectangle([doc_x0, doc_y0, doc_x1 - fold, doc_y1], radius=16, fill=(255, 255, 255, 255))

    # Folded flap
    flap = [
        (doc_x1 - fold, doc_y0),
        (doc_x1, doc_y0 + fold),
        (doc_x1 - fold, doc_y0 + fold)
    ]
    d_draw.polygon(flap, fill=(187, 222, 251, 255)) # Soft accent blue

    # Lines on document representing preserved text lines
    line_color = (21, 101, 192, 255) # Deep intact blue
    accent_color = (233, 30, 99, 255) # Magenta edit highlight

    # Line 1 (Accent edit line)
    d_draw.rounded_rectangle([doc_x0 + 36, doc_y0 + 90, doc_x1 - 36, doc_y0 + 104], radius=7, fill=line_color)
    # Line 2
    d_draw.rounded_rectangle([doc_x0 + 36, doc_y0 + 130, doc_x1 - 60, doc_y0 + 144], radius=7, fill=line_color)
    # Line 3 (Selected / active line with cursor/bounding box)
    d_draw.rounded_rectangle([doc_x0 + 32, doc_y0 + 164, doc_x1 - 32, doc_y0 + 196], radius=6, outline=accent_color, width=3)
    d_draw.rounded_rectangle([doc_x0 + 40, doc_y0 + 175, doc_x1 - 70, doc_y0 + 185], radius=5, fill=accent_color)
    # Line 4
    d_draw.rounded_rectangle([doc_x0 + 36, doc_y0 + 216, doc_x1 - 44, doc_y0 + 230], radius=7, fill=line_color)
    # Line 5
    d_draw.rounded_rectangle([doc_x0 + 36, doc_y0 + 252, doc_x1 - 80, doc_y0 + 266], radius=7, fill=(144, 202, 249, 255))

    icon_path = os.path.join(OUTPUT_DIR, "icon-512x512.png")
    bg.save(icon_path, "PNG")
    print(f"Generated: {icon_path}")

# -------------------------------------------------------------
# 2. FEATURE GRAPHIC (1024x500)
# -------------------------------------------------------------
def generate_feature_graphic():
    size = (1024, 500)
    img = Image.new("RGBA", size, (15, 23, 42, 255)) # Modern deep slate navy
    draw = ImageDraw.Draw(img)

    # Subtle background ambient glow on left and right
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    g_draw = ImageDraw.Draw(glow)
    g_draw.ellipse([50, -50, 450, 350], fill=(30, 136, 229, 45))
    g_draw.ellipse([650, 150, 1100, 600], fill=(233, 30, 99, 35))
    glow = glow.filter(ImageFilter.GaussianBlur(60))
    img = Image.alpha_composite(img, glow)
    draw = ImageDraw.Draw(img)

    # Left content: Typography & Badges
    font_badge = get_font(14, bold=True)
    font_title = get_font(44, bold=True)
    font_sub = get_font(20, bold=False)
    font_feature = get_font(15, bold=False)

    # Badge: %100 Çevrimdışı & Güvenli
    draw.rounded_rectangle([60, 68, 280, 102], radius=17, fill=(16, 185, 129, 230))
    draw.text((78, 75), "✓ %100 ÇEVRİMDISI & GÜVENLİ", fill=(255, 255, 255), font=font_badge)

    # Main Title
    draw.text((60, 120), "IntactPDF", fill=(255, 255, 255), font=font_title)
    draw.text((60, 180), "Sayfa Düzenini Koruyan\nPDF Düzenleyici", fill=(148, 163, 184), font=font_sub)

    # Bullet features with checkmarks
    features = [
        "Metinleri doğrudan yerinde değiştirin veya silin",
        "Manyetik hizalama çizgileriyle serbestçe taşıyın",
        "Yeni metinler ve boş sayfalar ekleyin",
        "İnternet izni istemez, verileriniz cihazınızda kalır"
    ]
    y_feat = 265
    for feat in features:
        draw.ellipse([62, y_feat+3, 76, y_feat+17], fill=(30, 136, 229, 255))
        draw.text((88, y_feat), feat, fill=(226, 232, 240), font=font_feature)
        y_feat += 36

    # Open Source tag at bottom left
    draw.text((60, 435), "GPLv3 Açık Kaynak • Reklamsız • Tamamen Ücretsiz", fill=(100, 116, 139), font=get_font(13))

    # Right side: Realistic App Device / Document Preview
    mock_x0, mock_y0 = 620, 45
    mock_x1, mock_y1 = 960, 455

    # Document Card with realistic drop shadow
    doc_shadow = Image.new("RGBA", size, (0, 0, 0, 0))
    ds_draw = ImageDraw.Draw(doc_shadow)
    ds_draw.rounded_rectangle([mock_x0-10, mock_y0+10, mock_x1+10, mock_y1+16], radius=20, fill=(0, 0, 0, 160))
    doc_shadow = doc_shadow.filter(ImageFilter.GaussianBlur(14))
    img = Image.alpha_composite(img, doc_shadow)
    draw = ImageDraw.Draw(img)

    # Document white canvas
    draw.rounded_rectangle([mock_x0, mock_y0, mock_x1, mock_y1], radius=16, fill=(255, 255, 255, 255))

    # Top appbar simulation on card
    draw.rounded_rectangle([mock_x0, mock_y0, mock_x1, mock_y0 + 52], radius=16, fill=(241, 245, 249, 255))
    draw.rectangle([mock_x0, mock_y0 + 36, mock_x1, mock_y0 + 52], fill=(241, 245, 249, 255)) # Flat bottom
    draw.text((mock_x0 + 16, mock_y0 + 14), "Sözleşme_2026.pdf", fill=(15, 23, 42), font=get_font(15, bold=True))
    draw.rounded_rectangle([mock_x1 - 92, mock_y0 + 12, mock_x1 - 16, mock_y0 + 40], radius=14, fill=(30, 136, 229))
    draw.text((mock_x1 - 80, mock_y0 + 17), "Düzenle", fill=(255, 255, 255), font=get_font(12, bold=True))

    # Document body text simulation
    doc_text_y = mock_y0 + 75
    # Title line
    draw.text((mock_x0 + 24, doc_text_y), "HİZMET SÖZLEŞMESİ", fill=(30, 41, 59), font=get_font(16, bold=True))
    draw.line([(mock_x0 + 24, doc_text_y + 28), (mock_x1 - 24, doc_text_y + 28)], fill=(226, 232, 240), width=1)

    # Paragraph lines
    draw.text((mock_x0 + 24, doc_text_y + 44), "Madde 1: Taraflar ve Hizmet Kapsamı", fill=(71, 85, 105), font=get_font(13, bold=True))
    draw.text((mock_x0 + 24, doc_text_y + 70), "Bu sözleşme kapsamında sunulan hizmetler...", fill=(100, 116, 139), font=get_font(12))

    # Active editing highlight with magnetic guide
    active_y = doc_text_y + 115
    # Magenta vertical alignment guide line through page
    draw.line([(mock_x0 + 170, mock_y0 + 52), (mock_x0 + 170, mock_y1)], fill=(233, 30, 99, 220), width=2)
    # Badge on guide line
    draw.rounded_rectangle([mock_x0 + 120, mock_y0 + 60, mock_x0 + 220, mock_y0 + 80], radius=4, fill=(233, 30, 99))
    draw.text((mock_x0 + 132, mock_y0 + 64), "Sayfa Ortası", fill=(255, 255, 255), font=get_font(11, bold=True))

    # Dragged / active bounding box
    draw.rounded_rectangle([mock_x0 + 24, active_y, mock_x1 - 40, active_y + 36], radius=4, outline=(30, 136, 229), fill=(30, 136, 229, 30), width=2)
    draw.text((mock_x0 + 32, active_y + 9), "Yetkili İmza: Ensar Rasne", fill=(30, 136, 229), font=get_font(13, bold=True))

    # Normal text blocks with subtle blue boxes
    draw.rounded_rectangle([mock_x0 + 24, active_y + 54, mock_x1 - 80, active_y + 80], radius=3, outline=(144, 202, 249), fill=(241, 245, 249), width=1)
    draw.text((mock_x0 + 30, active_y + 60), "Tarih: 28 Eylül 2026", fill=(100, 116, 139), font=get_font(12))

    draw.rounded_rectangle([mock_x0 + 24, active_y + 96, mock_x1 - 120, active_y + 122], radius=3, outline=(144, 202, 249), fill=(241, 245, 249), width=1)
    draw.text((mock_x0 + 30, active_y + 102), "Belge Onay Kodu: #84920", fill=(100, 116, 139), font=get_font(12))

    # Bottom FAB floating button on card
    draw.rounded_rectangle([mock_x1 - 120, mock_y1 - 56, mock_x1 - 16, mock_y1 - 16], radius=18, fill=(30, 136, 229))
    draw.text((mock_x1 - 105, mock_y1 - 44), "+ Metin Ekle", fill=(255, 255, 255), font=get_font(13, bold=True))

    feat_path = os.path.join(OUTPUT_DIR, "feature-graphic-1024x500.png")
    img.save(feat_path, "PNG")
    print(f"Generated: {feat_path}")

# -------------------------------------------------------------
# HELPER: Draw Phone Frame & Status Bar (1080x2400)
# -------------------------------------------------------------
def create_base_phone_screen():
    size = (1080, 2400)
    img = Image.new("RGBA", size, (248, 250, 252, 255)) # Material Theme surfaceContainerLow
    draw = ImageDraw.Draw(img)

    # Modern Android Status Bar (top 90px)
    draw.text((70, 32), "12:30", fill=(30, 41, 59), font=get_font(34, bold=True))
    # Status bar icons on right (wifi, signal, battery)
    # Signal
    for i in range(4):
        draw.rounded_rectangle([860 + i*12, 54 - i*6, 868 + i*12, 66], radius=2, fill=(30, 41, 59))
    # Battery icon
    draw.rounded_rectangle([930, 36, 990, 68], radius=6, outline=(30, 41, 59), width=3)
    draw.rounded_rectangle([990, 46, 996, 58], radius=2, fill=(30, 41, 59))
    draw.rounded_rectangle([936, 42, 978, 62], radius=4, fill=(30, 41, 59)) # 80% charge

    # Modern Navigation Bar at bottom (line bar)
    draw.rounded_rectangle([420, 2360, 660, 2370], radius=5, fill=(148, 163, 184))

    return img

# -------------------------------------------------------------
# 3. SCREENSHOT 1: Açılış / Ana Sayfa (Welcome Screen)
# -------------------------------------------------------------
def generate_screenshot_1_welcome():
    img = create_base_phone_screen()
    draw = ImageDraw.Draw(img)

    # Top App Bar
    draw.rectangle([0, 90, 1080, 240], fill=(255, 255, 255))
    draw.line([(0, 240), (1080, 240)], fill=(226, 232, 240), width=2)
    draw.text((70, 140), "IntactPDF", fill=(15, 23, 42), font=get_font(52, bold=True))

    # Center Welcome Icon
    center_y = 620
    draw.ellipse([440, center_y, 640, center_y + 200], fill=(224, 242, 254))
    # Icon inner document
    draw.rounded_rectangle([505, center_y + 40, 575, center_y + 155], radius=10, fill=(30, 136, 229))
    draw.rounded_rectangle([520, center_y + 65, 560, center_y + 75], radius=3, fill=(255, 255, 255))
    draw.rounded_rectangle([520, center_y + 90, 560, center_y + 100], radius=3, fill=(255, 255, 255))
    draw.rounded_rectangle([520, center_y + 115, 550, center_y + 125], radius=3, fill=(255, 255, 255))

    # Welcome Headline & Subtitle
    draw.text((370, center_y + 230), "IntactPDF", fill=(15, 23, 42), font=get_font(64, bold=True))
    draw.text((120, center_y + 320), "Sayfa düzenini bozmadan PDF metinlerini\ndoğrudan düzenleyin ve kaydedin.", fill=(100, 116, 139), font=get_font(40), align="center")

    # Primary Action Button: PDF Dosyası Seç
    btn_y = center_y + 490
    draw.rounded_rectangle([90, btn_y, 990, btn_y + 140], radius=36, fill=(30, 136, 229))
    draw.text((350, btn_y + 42), "📁  PDF Dosyası Seç", fill=(255, 255, 255), font=get_font(44, bold=True))

    # Secondary Action Button: Örnek Belgeyi İncele
    btn2_y = btn_y + 170
    draw.rounded_rectangle([90, btn2_y, 990, btn2_y + 130], radius=36, outline=(203, 213, 225), fill=(255, 255, 255), width=3)
    draw.text((330, btn2_y + 38), "📖  Örnek Belgeyi İncele", fill=(30, 41, 59), font=get_font(42, bold=True))

    # Feature Highlights Card
    card_y = btn2_y + 190
    draw.rounded_rectangle([90, card_y, 990, card_y + 440], radius=36, fill=(255, 255, 255), outline=(226, 232, 240), width=2)

    rows = [
        ("👁️", "Görüntüleme ve Düzenleme modları", "Tek dokunuşla okuma veya düzenleme modu"),
        ("✏️", "Canlı Metin Değiştirme ve Silme", "Sayfa yapısını bozmadan pürüzsüz düzenleme"),
        ("⚡", "Manyetik Hizalama & Sayfa Ekleme", "Kılavuz çizgileriyle taşıma ve yeni sayfalar")
    ]
    ry = card_y + 40
    for icon, title, desc in rows:
        draw.text((130, ry), icon, font=get_font(40))
        draw.text((210, ry), title, fill=(15, 23, 42), font=get_font(36, bold=True))
        draw.text((210, ry + 48), desc, fill=(100, 116, 139), font=get_font(28))
        ry += 130

    # Privacy Policy link at bottom
    draw.text((370, 2220), "🛡️  Gizlilik Politikası (100% Çevrimdışı)", fill=(30, 136, 229), font=get_font(32, bold=True))

    path = os.path.join(OUTPUT_DIR, "screenshot-1-welcome.png")
    img.save(path, "PNG")
    print(f"Generated: {path}")

# -------------------------------------------------------------
# 4. SCREENSHOT 2: Temiz Belge Görüntüleme Modu (View Mode)
# -------------------------------------------------------------
def generate_screenshot_2_view():
    img = create_base_phone_screen()
    draw = ImageDraw.Draw(img)

    # Top App Bar
    draw.rectangle([0, 90, 1080, 260], fill=(255, 255, 255))
    draw.line([(0, 260), (1080, 260)], fill=(226, 232, 240), width=2)
    draw.text((70, 125), "Örnek_Rapor.pdf", fill=(15, 23, 42), font=get_font(44, bold=True))
    draw.text((70, 185), "Sayfa 1 / 1  •  Okuma Modu", fill=(100, 116, 139), font=get_font(28))

    # Action buttons: Mode Toggle ("Düzenle" button)
    draw.rounded_rectangle([720, 135, 930, 215], radius=40, fill=(224, 242, 254))
    draw.text((760, 155), "✏️ Düzenle", fill=(21, 101, 192), font=get_font(32, bold=True))
    # Three dot menu
    draw.text((975, 140), "⋮", fill=(71, 85, 105), font=get_font(52, bold=True))

    # PDF Document Display Area (Centered A4 Page)
    doc_x0, doc_y0 = 70, 310
    doc_x1, doc_y1 = 1010, 2100

    # Document drop shadow
    sh = Image.new("RGBA", (1080, 2400), (0, 0, 0, 0))
    s_draw = ImageDraw.Draw(sh)
    s_draw.rounded_rectangle([doc_x0-8, doc_y0+8, doc_x1+8, doc_y1+14], radius=24, fill=(0, 0, 0, 45))
    sh = sh.filter(ImageFilter.GaussianBlur(12))
    img = Image.alpha_composite(img, sh)
    draw = ImageDraw.Draw(img)

    # Document white background
    draw.rounded_rectangle([doc_x0, doc_y0, doc_x1, doc_y1], radius=20, fill=(255, 255, 255))

    # Render Document Content (Clean view, zero overlays!)
    # Header title
    draw.text((doc_x0 + 70, doc_y0 + 100), "IntactPDF Örnek Belge", fill=(15, 23, 42), font=get_font(58, bold=True))
    draw.text((doc_x0 + 70, doc_y0 + 180), "Sayfa Düzenini Koruyan PDF Düzenleyici", fill=(30, 136, 229), font=get_font(36, bold=True))
    draw.line([(doc_x0 + 70, doc_y0 + 240), (doc_x1 - 70, doc_y0 + 240)], fill=(203, 213, 225), width=3)

    paragraphs = [
        "Bu metin üzerine dokunarak doğrudan düzenleyebilirsiniz.",
        "Düzenleme sonrasında sayfa düzeni ve satır yapısı asla bozulmaz.",
        "İstediğiniz metni silebilir veya yerine yeni metin yazabilirsiniz.",
        "Manyetik hizalama çizgileriyle blokları sayfa ortasına yaslayabilirsiniz.",
        "Kaydet butonuna basarak düzenlenmiş belgenizi dışa aktarabilirsiniz."
    ]
    py = doc_y0 + 310
    for p in paragraphs:
        draw.text((doc_x0 + 70, py), p, fill=(51, 65, 85), font=get_font(36))
        py += 150

    # Bottom Bar: Page Navigation
    draw.rectangle([0, 2160, 1080, 2330], fill=(255, 255, 255))
    draw.line([(0, 2160), (1080, 2160)], fill=(226, 232, 240), width=2)
    draw.text((120, 2220), "◀  Önceki", fill=(148, 163, 184), font=get_font(34, bold=True))
    draw.text((470, 2220), "1 / 1", fill=(15, 23, 42), font=get_font(38, bold=True))
    draw.text((820, 2220), "Sonraki  ▶", fill=(148, 163, 184), font=get_font(34, bold=True))

    path = os.path.join(OUTPUT_DIR, "screenshot-2-view.png")
    img.save(path, "PNG")
    print(f"Generated: {path}")

# -------------------------------------------------------------
# 5. SCREENSHOT 3: Düzenleme Modu & Metin Seçimi (Edit Mode)
# -------------------------------------------------------------
def generate_screenshot_3_edit():
    img = create_base_phone_screen()
    draw = ImageDraw.Draw(img)

    # Top App Bar in EDIT MODE
    draw.rectangle([0, 90, 1080, 260], fill=(255, 255, 255))
    draw.line([(0, 260), (1080, 260)], fill=(226, 232, 240), width=2)
    draw.text((70, 125), "Örnek_Rapor.pdf", fill=(15, 23, 42), font=get_font(44, bold=True))
    draw.text((70, 185), "Düzenleme Modu • Sayfa 1 / 1", fill=(30, 136, 229), font=get_font(28, bold=True))

    # Action buttons: Mode Toggle ("Oku" active) & Undo button
    draw.text((640, 150), "↩", fill=(71, 85, 105), font=get_font(48, bold=True))
    draw.rounded_rectangle([720, 135, 930, 215], radius=40, fill=(30, 136, 229))
    draw.text((780, 155), "👁️ Oku", fill=(255, 255, 255), font=get_font(32, bold=True))
    draw.text((975, 140), "⋮", fill=(71, 85, 105), font=get_font(52, bold=True))

    # Edit Banner Hint
    draw.rectangle([0, 260, 1080, 340], fill=(238, 242, 255))
    draw.text((220, 282), "👆  Düzenlemek için dokunun, taşımak için sürükleyin", fill=(67, 56, 202), font=get_font(30, bold=True))

    # PDF Document Display Area
    doc_x0, doc_y0 = 70, 390
    doc_x1, doc_y1 = 1010, 2100
    draw.rounded_rectangle([doc_x0, doc_y0, doc_x1, doc_y1], radius=20, fill=(255, 255, 255), outline=(203, 213, 225), width=2)

    # Document texts with Interactive Bounding Boxes
    draw.text((doc_x0 + 70, doc_y0 + 100), "IntactPDF Örnek Belge", fill=(15, 23, 42), font=get_font(58, bold=True))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 90, doc_x0 + 640, doc_y0 + 170], radius=8, outline=(30, 136, 229), fill=(30, 136, 229, 20), width=2)

    draw.text((doc_x0 + 70, doc_y0 + 190), "Sayfa Düzenini Koruyan PDF Düzenleyici", fill=(30, 136, 229), font=get_font(36, bold=True))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 180, doc_x0 + 750, doc_y0 + 240], radius=8, outline=(30, 136, 229), fill=(30, 136, 229, 20), width=2)

    draw.line([(doc_x0 + 70, doc_y0 + 260), (doc_x1 - 70, doc_y0 + 260)], fill=(203, 213, 225), width=3)

    # Selected Block Highlighted!
    draw.text((doc_x0 + 70, doc_y0 + 330), "Bu metin üzerine dokunarak doğrudan düzenleyebilirsiniz.", fill=(15, 23, 42), font=get_font(36))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 315, doc_x1 - 60, doc_y0 + 385], radius=10, outline=(30, 136, 229), fill=(30, 136, 229, 40), width=3)

    # Remaining blocks
    draw.text((doc_x0 + 70, doc_y0 + 470), "Düzenleme sonrasında sayfa düzeni ve satır yapısı bozulmaz.", fill=(51, 65, 85), font=get_font(36))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 455, doc_x1 - 60, doc_y0 + 525], radius=8, outline=(30, 136, 229), fill=(30, 136, 229, 20), width=2)

    draw.text((doc_x0 + 70, doc_y0 + 610), "İstediğiniz metni silebilir veya yerine yeni metin yazabilirsiniz.", fill=(51, 65, 85), font=get_font(36))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 595, doc_x1 - 60, doc_y0 + 665], radius=8, outline=(30, 136, 229), fill=(30, 136, 229, 20), width=2)

    # Floating Action Button: + Metin Ekle
    draw.rounded_rectangle([660, 1950, 970, 2070], radius=60, fill=(30, 136, 229))
    draw.text((710, 1990), "➕  Metin Ekle", fill=(255, 255, 255), font=get_font(38, bold=True))

    # Bottom Bar: Page Navigation & Sayfa Ekle
    draw.rectangle([0, 2160, 1080, 2330], fill=(255, 255, 255))
    draw.line([(0, 2160), (1080, 2160)], fill=(226, 232, 240), width=2)
    draw.text((100, 2220), "◀  Önceki", fill=(148, 163, 184), font=get_font(34, bold=True))
    draw.text((380, 2220), "1 / 1", fill=(15, 23, 42), font=get_font(38, bold=True))
    draw.text((540, 2220), "Sonraki  ▶", fill=(148, 163, 184), font=get_font(34, bold=True))

    draw.rounded_rectangle([760, 2185, 1000, 2275], radius=30, fill=(241, 245, 249))
    draw.text((790, 2210), "📄 + Sayfa Ekle", fill=(30, 41, 59), font=get_font(30, bold=True))

    path = os.path.join(OUTPUT_DIR, "screenshot-3-edit.png")
    img.save(path, "PNG")
    print(f"Generated: {path}")

# -------------------------------------------------------------
# 6. SCREENSHOT 4: Metin Düzenleme ve Silme İletişim Kutusu
# -------------------------------------------------------------
def generate_screenshot_4_dialog():
    img = generate_screenshot_3_edit_as_image()
    # Dim background overlay
    dim = Image.new("RGBA", (1080, 2400), (0, 0, 0, 120))
    img = Image.alpha_composite(img, dim)
    draw = ImageDraw.Draw(img)

    # Modal Dialog Box in Center
    dia_x0, dia_y0 = 90, 720
    dia_x1, dia_y1 = 990, 1620

    # Dialog shadow
    dia_sh = Image.new("RGBA", (1080, 2400), (0, 0, 0, 0))
    d_draw = ImageDraw.Draw(dia_sh)
    d_draw.rounded_rectangle([dia_x0-10, dia_y0+10, dia_x1+10, dia_y1+20], radius=40, fill=(0, 0, 0, 120))
    dia_sh = dia_sh.filter(ImageFilter.GaussianBlur(16))
    img = Image.alpha_composite(img, dia_sh)
    draw = ImageDraw.Draw(img)

    # Dialog Body
    draw.rounded_rectangle([dia_x0, dia_y0, dia_x1, dia_y1], radius=36, fill=(255, 255, 255))

    # Dialog Header
    draw.text((dia_x0 + 60, dia_y0 + 60), "✏️  Metni Düzenle", fill=(15, 23, 42), font=get_font(48, bold=True))
    draw.text((dia_x0 + 60, dia_y0 + 125), "Değişiklik sayfa düzenini bozmadan uygulanır.", fill=(100, 116, 139), font=get_font(28))

    # Text Input Field Box
    draw.rounded_rectangle([dia_x0 + 60, dia_y0 + 190, dia_x1 - 60, dia_y0 + 440], radius=20, outline=(30, 136, 229), fill=(248, 250, 252), width=3)
    draw.text((dia_x0 + 85, dia_y0 + 225), "Bu metin üzerine dokunarak", fill=(15, 23, 42), font=get_font(38))
    draw.text((dia_x0 + 85, dia_y0 + 280), "doğrudan değiştirebilirsiniz.|", fill=(15, 23, 42), font=get_font(38))

    # Font size info badge
    draw.rounded_rectangle([dia_x0 + 60, dia_y0 + 480, dia_x0 + 380, dia_y0 + 540], radius=14, fill=(241, 245, 249))
    draw.text((dia_x0 + 80, dia_y0 + 495), "Yazı Tipi Boyutu: 12 pt", fill=(71, 85, 105), font=get_font(28, bold=True))

    # Delete Button with confirmation alert
    del_btn_y = dia_y0 + 600
    draw.rounded_rectangle([dia_x0 + 60, del_btn_y, dia_x0 + 320, del_btn_y + 110], radius=24, outline=(239, 68, 68), fill=(254, 242, 242), width=2)
    draw.text((dia_x0 + 105, del_btn_y + 32), "🗑️ Metni Sil", fill=(220, 38, 38), font=get_font(34, bold=True))

    # Action Buttons: Vazgeç & Uygula
    draw.rounded_rectangle([dia_x0 + 360, del_btn_y, dia_x0 + 580, del_btn_y + 110], radius=24, fill=(241, 245, 249))
    draw.text((dia_x0 + 415, del_btn_y + 32), "Vazgeç", fill=(71, 85, 105), font=get_font(34, bold=True))

    draw.rounded_rectangle([dia_x0 + 620, del_btn_y, dia_x1 - 60, del_btn_y + 110], radius=24, fill=(30, 136, 229))
    draw.text((dia_x0 + 675, del_btn_y + 32), "Uygula", fill=(255, 255, 255), font=get_font(34, bold=True))

    # Security / Confirmation Note at Bottom
    draw.text((dia_x0 + 60, dia_y0 + 770), "ℹ️ Silinen veya düzenlenen tüm metinler 'Geri Al' ile kurtarılabilir.", fill=(148, 163, 184), font=get_font(24))

    path = os.path.join(OUTPUT_DIR, "screenshot-4-edit-dialog.png")
    img.save(path, "PNG")
    print(f"Generated: {path}")

def generate_screenshot_3_edit_as_image():
    # Return screenshot 3 as PIL image object
    path = os.path.join(OUTPUT_DIR, "screenshot-3-edit.png")
    return Image.open(path)

# -------------------------------------------------------------
# 7. SCREENSHOT 5: Dinamik Manyetik Hizalama & Taşıma
# -------------------------------------------------------------
def generate_screenshot_5_alignment():
    img = create_base_phone_screen()
    draw = ImageDraw.Draw(img)

    # Top App Bar
    draw.rectangle([0, 90, 1080, 260], fill=(255, 255, 255))
    draw.line([(0, 260), (1080, 260)], fill=(226, 232, 240), width=2)
    draw.text((70, 125), "Sözleşme_Taslak.pdf", fill=(15, 23, 42), font=get_font(44, bold=True))
    draw.text((70, 185), "Düzenleme Modu • Metin Taşınıyor", fill=(233, 30, 99), font=get_font(28, bold=True))

    # Mode Toggle & Undo button
    draw.text((640, 150), "↩", fill=(71, 85, 105), font=get_font(48, bold=True))
    draw.rounded_rectangle([720, 135, 930, 215], radius=40, fill=(30, 136, 229))
    draw.text((780, 155), "👁️ Oku", fill=(255, 255, 255), font=get_font(32, bold=True))
    draw.text((975, 140), "⋮", fill=(71, 85, 105), font=get_font(52, bold=True))

    # Edit Banner Hint
    draw.rectangle([0, 260, 1080, 340], fill=(253, 242, 248)) # Soft pink
    draw.text((200, 282), "🧲  Manyetik hizalama: Kılavuz çizgilerine otomatik yapışır", fill=(190, 24, 93), font=get_font(30, bold=True))

    # PDF Document Display Area
    doc_x0, doc_y0 = 70, 390
    doc_x1, doc_y1 = 1010, 2100
    draw.rounded_rectangle([doc_x0, doc_y0, doc_x1, doc_y1], radius=20, fill=(255, 255, 255), outline=(203, 213, 225), width=2)

    # Vertical Page Center Alignment Guide (X = 540)
    guide_x = 540
    draw.line([(guide_x, doc_y0), (guide_x, doc_y1)], fill=(233, 30, 99, 255), width=3)
    # Badge at top of vertical guide
    draw.rounded_rectangle([guide_x - 110, doc_y0 + 20, guide_x + 110, doc_y0 + 70], radius=10, fill=(233, 30, 99))
    draw.text((guide_x - 85, doc_y0 + 28), "Sayfa Ortası", fill=(255, 255, 255), font=get_font(28, bold=True))

    # Horizontal Page Center Guide (Y = 1245)
    guide_y = 1245
    draw.line([(doc_x0, guide_y), (doc_x1, guide_y)], fill=(233, 30, 99, 255), width=3)
    # Badge on horizontal guide
    draw.rounded_rectangle([doc_x0 + 20, guide_y - 55, doc_x0 + 220, guide_y - 5], radius=10, fill=(233, 30, 99))
    draw.text((doc_x0 + 40, guide_y - 47), "Yatay Orta", fill=(255, 255, 255), font=get_font(28, bold=True))

    # Static blocks on page
    draw.text((doc_x0 + 70, doc_y0 + 130), "RESMİ BİLDİRİM VE SÖZLEŞME", fill=(15, 23, 42), font=get_font(52, bold=True))
    draw.rounded_rectangle([doc_x0 + 60, doc_y0 + 120, doc_x1 - 60, doc_y0 + 200], radius=8, outline=(144, 202, 249), fill=(241, 245, 249), width=1)

    draw.text((doc_x0 + 70, doc_y0 + 240), "İşbu belge taraflar arasındaki mutabakatı gösterir.", fill=(71, 85, 105), font=get_font(34))

    # ACTIVE DRAGGED BLOCK - Snapped directly to Center Guide!
    drag_w, drag_h = 620, 100
    drag_left = guide_x - (drag_w // 2)
    drag_top = guide_y - (drag_h // 2)

    # Shadow for dragged block
    b_shadow = Image.new("RGBA", (1080, 2400), (0, 0, 0, 0))
    bs_draw = ImageDraw.Draw(b_shadow)
    bs_draw.rounded_rectangle([drag_left-6, drag_top+8, drag_left+drag_w+6, drag_top+drag_h+14], radius=16, fill=(0, 0, 0, 70))
    b_shadow = b_shadow.filter(ImageFilter.GaussianBlur(10))
    img = Image.alpha_composite(img, b_shadow)
    draw = ImageDraw.Draw(img)

    # Dragged box styling: Bold primary outline and blue tint
    draw.rounded_rectangle([drag_left, drag_top, drag_left + drag_w, drag_top + drag_h], radius=12, outline=(30, 136, 229), fill=(224, 242, 254), width=4)
    draw.text((drag_left + 40, drag_top + 26), "📌  Onaylayan: Yönetim Kurulu", fill=(21, 101, 192), font=get_font(38, bold=True))

    # Hand touch indicator simulation
    touch_x, touch_y = guide_x + 80, guide_y + 40
    draw.ellipse([touch_x - 30, touch_y - 30, touch_x + 30, touch_y + 30], fill=(30, 136, 229, 90))
    draw.ellipse([touch_x - 14, touch_y - 14, touch_x + 14, touch_y + 14], fill=(30, 136, 229, 220))

    # Additional text blocks below
    draw.text((doc_x0 + 70, doc_y0 + 1050), "Ek Açıklamalar ve Koşullar:", fill=(15, 23, 42), font=get_font(36, bold=True))
    draw.text((doc_x0 + 70, doc_y0 + 1120), "Belge üzerinde yapılan tüm konum değişiklikleri pürüzsüzce kaydedilir.", fill=(100, 116, 139), font=get_font(32))

    # Bottom Bar
    draw.rectangle([0, 2160, 1080, 2330], fill=(255, 255, 255))
    draw.line([(0, 2160), (1080, 2160)], fill=(226, 232, 240), width=2)
    draw.text((100, 2220), "◀  Önceki", fill=(148, 163, 184), font=get_font(34, bold=True))
    draw.text((380, 2220), "1 / 1", fill=(15, 23, 42), font=get_font(38, bold=True))
    draw.text((540, 2220), "Sonraki  ▶", fill=(148, 163, 184), font=get_font(34, bold=True))

    draw.rounded_rectangle([760, 2185, 1000, 2275], radius=30, fill=(241, 245, 249))
    draw.text((790, 2210), "📄 + Sayfa Ekle", fill=(30, 41, 59), font=get_font(30, bold=True))

    path = os.path.join(OUTPUT_DIR, "screenshot-5-align-move.png")
    img.save(path, "PNG")
    print(f"Generated: {path}")

if __name__ == "__main__":
    print("Generating Store Assets...")
    generate_app_icon()
    generate_feature_graphic()
    generate_screenshot_1_welcome()
    generate_screenshot_2_view()
    generate_screenshot_3_edit()
    generate_screenshot_4_dialog()
    generate_screenshot_5_alignment()
    print("All store assets successfully created!")
