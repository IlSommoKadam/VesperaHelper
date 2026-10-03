#version 320 es

precision highp float;
precision highp int;
precision mediump float;
precision highp image2D;
precision highp uimage2D;

//out from vertex: fragPosition
in vec4 fragPosition;
out vec4 fragColor;

//unused
const int LST_SIZE = 100;//Taille de 100 par lignes
const int NB_LST = 3;//Nombre de lignes

uniform int uEnableLineAnimation;
uniform float uRecordSoundIntensity;
uniform float uMinValue;
uniform float uMaxValue;
uniform float uWake01;
uniform float uActive01;
uniform float uFadeLogo;

layout(std430, binding = 1) buffer DataBuffer {
    int values[300];
};

//used
uniform int uFullScreen;
uniform int endAnimation;
uniform float uCircleRadius;
uniform float uAccelerate01;
uniform float uBarWidth;
uniform float uWidth;
uniform float uHeight;
uniform float uTime;

const vec4 colors[3] = vec4[](
vec4(0.0, 0.5, 0.7, 1.0),
vec4(0.05490, 0.47843, 0.99608f, 1.0),
vec4(0.0, 0.65, 0.88, 1.0)
);

// Fonction pour calculer la distance angulaire normalisée
float getAngleDistance01(float pxAngle01, float arcAngle01) {
    float diff = abs(pxAngle01 - arcAngle01);
    return min(diff, 1.0 - diff); // Distance circulaire normalisée
}

float getAngle01(vec2 position) {
    float pxAngle = atan(position.y, position.x) + 3.14159265359 * 0.5;
    pxAngle = mod(pxAngle, 2.0 * 3.14159265359);
    if (pxAngle < 0.0) {
        pxAngle += 2.0 * 3.14159265359;
    }
    return 1.0 - (pxAngle / (2.0 * 3.14159265359));
}

// Ease out back function (transition easing)
float easeOutBack(float x) {
    float c1 = 1.70158;
    float c3 = c1 + 1.0;
    return 1.0 + c3 * pow(x - 1.0, 3.0) + c1 * pow(x - 1.0, 2.0);
}

float readLineValue(float x01, int shapeIndex)
{
    float sampleFloatIndex =  x01 * floor(float(LST_SIZE - 1));

    int sampleIndex = int(sampleFloatIndex) + shapeIndex * LST_SIZE;

    int intV1 = values[sampleIndex];
    int intV2 = values[sampleIndex + 1];

    //On divise par le facteur multiplicatif pour
    float v1 = float(intV1) / 1000000.0;
    float v2 = float(intV2) / 1000000.0;

    // Interpolation linéaire entre les deux échantillons
    return mix(v1, v2,  fract(sampleFloatIndex));
}

vec4 getLineColor(float pxRadius01, float pxAngle01, int shapeIndex, vec4 shapeColor, float minValue)
{
    // Lire la valeur du gradient pour l'angle
    float gradientMaxRadius01 = readLineValue(pxAngle01, shapeIndex);
    float gradientMinRadius01 = minValue;  // Valeur minimale du rayon

    float gradientX01 = (pxRadius01 - gradientMinRadius01) / max(gradientMaxRadius01 - gradientMinRadius01, 0.00001);

    gradientX01 = clamp(gradientX01, 0.0, 1.0);  // Clamer dans la plage [0, 1]

    // Déterminer la couleur de la ligne
    vec4 color = shapeColor;
    color.a = 1.0 - gradientX01;  // Opacité inversée selon le gradient

    // Pré-multiplication de l'alpha
    color.rgb *= color.a;

    return color;
}

struct ArcColors {
    vec4 arc;
    vec4 glow;
};

ArcColors getArcColors(float pxRadius01, float pxAngle01, float arcAngleOffset, float inProgress, float ratioToPixel) {
    const float PI = 3.14159265359;

    //Valeurs pour les arcs du wake
    const float numTurns = 6.0;
    const float cycles  = 2.5;
    // Plage de taille de l’arc (en fraction d’un tour). Ajuste ces bornes si besoin.
    const float ARC_SIZE_MIN = 0.10;
    const float ARC_SIZE_MAX = 0.20;
    // Valeurs pour le GLOW
    const float GLOW_STRENGTH      = 1.2;//gere l'amplitude du halo
    const float GLOW_ARC_SIZE_MULT = 1.5;//augmente l'arc du halo
    const float GLOW_OUT_MULT      = 1.20;
    const float GLOW_IN_MULT       = 1.50;
    const float GLOW_ALPHA_FLOOR   = 0.12;

    // pxAngle01 arrive déjà en [0..1) (cf. ta getAngle01). Sécurise juste avec fract.
    float pxAngle01_norm = fract(pxAngle01);

    // Rotation + taille angulaire de l’arc
    float rotationProgress = inProgress * inProgress * inProgress; // cubic easing
    float arcAngle01 = fract(rotationProgress * numTurns + arcAngleOffset);
    // Utilise un décalage de phase si tu veux “verrouiller” la taille au même rythme
    // que la rotation. Tu peux aussi essayer: float phase01 = fract(ratioToPixel);
    float phase01 = fract(arcAngleOffset);
    // tri01 ∈ [0..1], va min→max→min de façon linéaire, répété $cycles fois
    float tri01   = 1.0 - abs(fract(cycles * inProgress + phase01) * 2.0 - 1.0);

    float arcSize = mix(ARC_SIZE_MIN, ARC_SIZE_MAX, tri01);

    // Pulse de base (0..1). On s'en sert pour le halo ; l’arc n’en dépend plus directement.
    float phaseOffset = arcAngleOffset == 0.0 ? 0.0 : 0.5;
    float s = sin((inProgress * numTurns + phaseOffset) * 2.0 * PI);
    float instanceProgress = 0.5 * (s + 1.0); // 0..1

    // --- Pulse pour le halo: plancher pour éviter extinction ---
    const float PULSE_FLOOR = 0.75; // augmente si tu veux un halo encore plus constant
    float pulse = max(instanceProgress, PULSE_FLOOR);

    float arcThicknessPx = mix(1.0, 15.0, inProgress / 2.0);
    arcThicknessPx = max(arcThicknessPx, 0.75);
    float arcThickness = arcThicknessPx / ratioToPixel;

    float pxRadiusDistance01 = abs(abs(0.47 - pxRadius01) - (1.0 + 1.2 * arcThickness)) / arcThickness;

    // ================= GLOW  =================
    vec4 glow = colors[1];
    glow.a = 1.0; // on ignore l’alpha de la palette pour le halo

    // Courbe d'intensité monotone avec fade-out en fin d'anim (pas de clignotement)
    const float glowFadeOutDuration = 0.10; // même logique que chez toi
    float glowIntensity = (inProgress < (1.0 - glowFadeOutDuration))
    ? clamp(3.0 * inProgress / (1.0 - glowFadeOutDuration), 0.0, 1.0)
    : 1.0 - (inProgress - (1.0 - glowFadeOutDuration)) / glowFadeOutDuration;
    // plancher pour ne jamais s’éteindre complètement
    glowIntensity = max(glowIntensity, 0.25);

    // Masque angulaire wrap-safe (conserve ton "téton" côté angle)
    float glowArcSize       = arcSize * GLOW_ARC_SIZE_MULT + 1e-4;
    float glowArcDistance01 = getAngleDistance01(fract(pxAngle01), arcAngle01);
    float glowAngMask       = 1.0 - clamp(glowArcDistance01 / glowArcSize, 0.0, 1.0);
    glowAngMask = smoothstep(0.0, 1.0, glowAngMask);

    //On va augmenter l'intensité du halo avec une forme sonusoidale
    float dAng = fract((pxAngle01_norm - arcAngle01) + 0.5) - 0.5;
    float u = clamp(0.5 + dAng / (2.0 * glowArcSize), 0.0, 1.0);
    float glowAng = sin(PI * u);

    // Optionnel: façonne la bosse (0.8 = plus dodu, 1.2 = plus doux)
    glowAng = pow(glowAng, 1.0);

    // Masque radial "à l’ancienne" (le fameux téton) — taille *optionnellement* liée à inProgress
    float glowThicknessOutside = mix(0.2, 0.7, glowIntensity) * GLOW_OUT_MULT; // évolue avec inProgress
    float glowThicknessInside  = mix(0.03, 0.08, glowIntensity) * GLOW_IN_MULT;

    float glowThicknessAlpha = (pxRadius01 < 0.0)
    ? (3.2 + pxRadius01 / glowThicknessInside)   // Inner glow (rarement pris si pxRadius01∈[0..1])
    : (2.8 - pxRadius01 / glowThicknessOutside); // Outer glow

    // Alpha final : plancher + intensité pilotée par inProgress (pas de sin → pas de blink)
    float aRadial = clamp(glowThicknessAlpha, 0.0, 1.0);

    // --- booster blanc au centre de l'arc ---
    const float WHITE_TINT_GAIN   = 0.7;  // 0..1 : quantité de "tirage vers le blanc"
    const float WHITE_BRIGHT_GAIN = 0.25; // gain additif de luminance (optionnel)
    const float WHITE_SHARPNESS   = 2.0;  // >1 = plus piqué au centre

    // masque centré (0 aux bords, 1 au centre)
    float centerMask = pow(sin(PI * u), WHITE_SHARPNESS);

    // (optionnel) restreindre au lobe radial réel pour éviter d'éclairer trop loin
    centerMask *= aRadial;

    // 1) tirer la teinte bleutée vers le blanc au centre
    glow.rgb = mix(glow.rgb, vec3(1.0), WHITE_TINT_GAIN * centerMask);

    // 2) (optionnel) petit ajout de luminance au centre
    glow.rgb += vec3(WHITE_BRIGHT_GAIN * centerMask);

    float angLobe = mix(GLOW_ALPHA_FLOOR, 1.0, glowAng);        // 0→floor, 1→1 en sinus
    float base    = aRadial * angLobe;

    // petit souffle via le pulse (doux, jamais 0)
    float pulseAmp = mix(0.92, 1.08, pulse);//GLOW_STRENGTH
    glow.a = clamp(base * glowIntensity * pulseAmp * (GLOW_STRENGTH + tri01 - 0.5), 0.0, 1.0);

    glow.rgb *= 1.15;

    // ----------------- ARC -----------------
    vec4 arcPx = vec4(1.0); // blanc

    // Masque angulaire de l’arc (wrap-safe)
    float arcDistance01 = getAngleDistance01(pxAngle01_norm, arcAngle01);
    arcDistance01 = clamp(arcDistance01 / arcSize, 0.0, 1.0);
    arcPx.a *= clamp(2.0 * (1.0 - arcDistance01), 0.0, 1.0);

    // Placement radial (ta formule “qui marche” conservée)
    arcPx.a *= clamp(1.0 - pxRadiusDistance01, 0.0, 1.0);

    // Fade-in de départ
    arcPx.a *= clamp(inProgress * 5.0, 0.0, 1.0);

    // Option: petit “souffle” d’alpha qui ne tombe jamais à 0 (au lieu de *instanceProgress)
    arcPx.a *= (0.85 + 0.15 * instanceProgress);

    // Légère amplification visuelle
    arcPx.a = clamp(arcPx.a * 1.3, 0.0, 1.0);

    // Résultat
    ArcColors result;
    result.arc  = arcPx;
    result.glow = glow;
    return result;
}


vec4 fullScreenMode(vec2 size, vec2 positionPx, float circleRadiusPx)
{
    float pxRadius01;
    float pxAngle01;
    float pxCirc01;

    vec2 pxPosition01 = (positionPx - 0.5 * size) / circleRadiusPx;
    pxRadius01 = length(pxPosition01);//Si on ajoute de la multiplication ici: cela diminue la taille du cercle
    pxAngle01 = getAngle01(pxPosition01);
    pxCirc01 = pxAngle01;

    // Calculer le maximum alpha basé sur la phase actuelle de l'animation
    float maxLineAlpha;
    if (uAccelerate01 < 1.0) {
        maxLineAlpha = mix(0.7, 1.0, pow(uAccelerate01, 4.0));  // Utilisation de `pow` pour la courbe d'accélération
    } else {
        maxLineAlpha = mix(0.7, 1.0, uActive01);  // Utilisation de `mix` pour interpoler
    }

    // Initialiser la couleur du pixel
    vec4 pxColor = vec4(0.0);

    // Calculer les couleurs des différentes lignes et les fusionner
    for (int i = 0; i < NB_LST; i++)
    {
        vec4 lineColor = getLineColor(pxRadius01, pxCirc01, i, colors[i], uMinValue);
        pxColor.rgb += lineColor.rgb;
        lineColor.a *= maxLineAlpha;
        pxColor.a += lineColor.a;
    }

    //    // Clamper les valeurs de couleur RGB et alpha
    pxColor.rgb = clamp(pxColor.rgb, 0.0, 1.0);
    pxColor.a = clamp(pxColor.a, 0.0, 1.0);

    // Calculer l'effet de lumière blanche uniquement si l'animation est à sa phase finale
    //TODO>> faire dispraitre pour l'animation finale
    if (uAccelerate01 == 1.0)
    {
        // Calculer l'épaisseur de la couche blanche selon l'écran entier ou non
//        float whiteThickness01 = 70.0 / (circleRadiusPx / 2.0 );
        float whiteThickness01 = 0.65;//Mis en dur: peut etre tenter un ajustement sur d'autres dimensions

        // Calculer la distance de l'effet de lumière blanche (clampée entre 0 et 1)
        float whiteDistance01 = clamp((pxRadius01 - 1.1) / whiteThickness01, 0.0, 1.0);
        float whiteOpacity = (1.0 + cos(whiteDistance01 * 3.14159265359)) * 0.5;

        // Ajuster l'effet en fonction de l'état actif
        float whiteOpacityProgress = clamp(uActive01, 0.0, 1.0);
        whiteOpacity *= 1.8;//mix(0.4, 0.8, whiteOpacityProgress);

        // Définir la couleur blanche (blanc pour l'instant, mais peut être ajusté pour des débogages)
        vec3 whiteOpacityColor = vec3(1.0, 1.0, 1.0);
        // Ajouter l'effet de lumière blanche à la couleur existante
        pxColor.rgb = whiteOpacityColor * whiteOpacity + (1.0 - whiteOpacity) * pxColor.rgb;

        // Ajuster l'alpha avec l'opacité de la lumière blanche
        pxColor.a = clamp(pxColor.a + whiteOpacity, 0.0, 1.0);
    }

    //Autre code : validé
    float smoothThickness01 = 4.0 / circleRadiusPx;// épaisseur du lissage
    float distanceToEdge = abs(0.5 - pxRadius01);
    // Appliquez un anticrénelage basé sur la distance au bord
    float antialiasing = smoothstep(1.0 - smoothThickness01, 1.0, distanceToEdge);
    pxColor = mix(
        vec4(0.0, 0.0, 0.0, 1.0),  // Couleur noire opaque (arrière-plan)
        pxColor,                    // Couleur du cercle
        antialiasing                // Facteur de lissage basé sur la distance au bord
    );

    //code suivi X5
    if (uWake01 < 1.0) {
        float opacityDuration = 0.8;
        float opacityP = clamp(uWake01 / opacityDuration, 0.0, 1.0);
        opacityP = 1.0 - cos(opacityP * 3.14159265359 / 2.0);

        float wakeFade01 = 0.1;
        float wakeAngle01 = opacityP * (1.0 + wakeFade01);

        // Calcul de la distance du pixel par rapport aux bords gauche et droit de l'arc
        float pxDistanceToLeftEdge = pxAngle01;
        float pxDistanceToRightEdge = clamp(wakeAngle01 - pxAngle01, 0.0, 1.0);
        float pxDistance = min(pxDistanceToLeftEdge, pxDistanceToRightEdge);

        //La valeur permet bien de dessiner un cercle
        float wake01 = pxDistance / wakeFade01;

        if (wakeAngle01 > 1.0 && pxDistanceToLeftEdge < wakeFade01) {
            wake01 = max(wake01, (wakeAngle01 - 1.0) / wakeFade01);
        }

        // L'opacity est plus fine sur le bord de l'arc du wake (gradient d'opacité)
        wake01 = clamp(wake01, 0.0, 1.0);

        // Opacité globale plus fine pendant l'éveil et plus brillante à la fin
        float factorDuration = 0.25;
        float factorP = (uWake01 - (1.0 - factorDuration)) / factorDuration;
        factorP = clamp(factorP, 0.0, 1.0);
        factorP = easeOutBack(factorP);
        wake01 *= mix(0.8, 1.0, factorP);

        pxColor.a *= wake01;
    }

    //code suivit X6
    if (uAccelerate01 > 0.0 && uAccelerate01 < 1.0) {
        // Obtenir les couleurs de l'arc (supposons que getArcColors est une fonction déjà définie)
        ArcColors arc1 = getArcColors(pxRadius01, pxAngle01, 0.0, uAccelerate01, circleRadiusPx);
        ArcColors arc2 = getArcColors(pxRadius01, pxAngle01, 0.4, uAccelerate01, circleRadiusPx);

        vec4 g1 = arc1.glow;
        vec4 g2 = arc2.glow;

        // alpha "union" (pas de mur)
        float glowA = 1.0 - (1.0 - g1.a) * (1.0 - g2.a);

        // couleur pondérée par alpha (couleurs non prémultipliées ici)
        vec3 glowRGB = (g1.rgb * g1.a + g2.rgb * g2.a) / max(glowA, 1e-4);

        // (facultatif) petite "soft-knee" anti-écrêtage si tes halos sont puissants
         glowRGB = glowRGB / (1.0 + glowRGB); // Reinhard simple

        vec4 glow = vec4(glowRGB, glowA);

        // --- ARC (même logique) ---
        vec4 a1 = arc1.arc;
        vec4 a2 = arc2.arc;

        float arcA  = 1.0 - (1.0 - a1.a) * (1.0 - a2.a);
        vec3  arcRGB = (a1.rgb * a1.a + a2.rgb * a2.a) / max(arcA, 1e-4);
        vec4  arc = vec4(arcRGB, arcA);

        // Prémultiplier la lueur par son alpha
        glow.rgb *= glow.a;

        // Dessiner la lueur derrière le pixel actuel
        pxColor.rgb = pxColor.rgb + glow.rgb * (1.0 - pxColor.a);
        pxColor.a = pxColor.a + glow.a * (1.0 - pxColor.a);

        // Ajouter l'arc à la couleur du pixel
        pxColor.rgb = clamp(pxColor.rgb * (1.0 - arc.a) + arc.rgb * arc.a, 0.0, 1.0);
        pxColor.a = clamp(pxColor.a + arc.a, 0.0, 1.0);
    }

    pxColor.a = pxColor.a * uFadeLogo;

    return pxColor;
}

vec4 audioPillLector(vec2 size, vec2 positionPx, float circleRadiusPx)
{
    bool isInPill = false;//Booléen pour repérer les points qui sont dans la partie centrale de la pillule

    float pxRadius01;
    float pxAngle01;
    float pxCirc01;

    const float PI = 3.14159265359;

    float toRemove = size.y * 0.5;

    float uBarWidth1 = size.x - toRemove;
    float uBarHeight = size.y - toRemove;

    float barWidthMargin = (size.x - uBarWidth1) / 2.0;
    float barHeightMargin = (size.y - uBarHeight) / 2.0;

    // capsule
    // handle right alignment (cut the left part of the anim if barWidth is too small)
    float usableWidth = uBarWidth1;//uBarWidth1 + 2.0 * marginPx;

    // take a direct angle with the center // Pas compris
    pxAngle01 = getAngle01(positionPx - vec2(usableWidth * 0.5, size.y * 0.5));

    vec2 offsetFromCenter = vec2(0.0);

    float r = 0.5 * uBarHeight;        // rayon = moitié de la hauteur utile
    // si tu veux, force la cohérence :
    circleRadiusPx = r;

    float leftCenterX  = barWidthMargin + r;
    float rightCenterX = barWidthMargin + uBarWidth1 - r;
    float centerY      = barHeightMargin + r;
    // si tu utilises ta règle 0..1 du périmètre :
    float rectWidth = max(uBarWidth1 - 2.0 * r, 0.0);
    float rectLenU  = rectWidth / max(r, 1e-6);
    float totalU    = 2.0 * PI + 2.0 * rectLenU;
    float rectFrac  = rectLenU / totalU;
    float halfFrac  = PI / totalU;

    if (positionPx.x <= leftCenterX)
    {
        // demi-cercle gauche
        offsetFromCenter = positionPx - vec2(leftCenterX, centerY);

        // angle global dans [0, 2π)
        float a = atan(offsetFromCenter.y, offsetFromCenter.x); // [-π, π]
        if (a < 0.0) a += 2.0 * PI;                             // [0, 2π)

        // progression locale : 0 en haut (π/2) -> 1 en bas (3π/2)
        float local01 = (a - 0.5 * PI) / PI;                    // [0, 1] sur le demi-cercle gauche
        local01 = clamp(local01, 0.0, 1.0);

        // ordre global: segment haut -> demi-droit -> segment bas -> demi-gauche
        pxCirc01 = rectFrac + halfFrac + rectFrac + local01 * halfFrac;
    }
    else if (positionPx.x >= rightCenterX)
    {
        // demi-cercle droit
        offsetFromCenter = positionPx - vec2(rightCenterX, centerY);

        // progression locale du demi-cercle droit : 0 en haut -> 1 en bas
        float a = atan(offsetFromCenter.y, offsetFromCenter.x); // [-pi, pi], 0 sur +X
        float local01 = (0.5 * PI + a) / PI;                    // map haut->bas
        local01 = clamp(local01, 0.0, 1.0);

        // Règle 0..1 du périmètre (ordre choisi) :
        // segment haut -> demi-cercle droit -> segment bas -> demi-cercle gauche
        pxCirc01 = rectFrac + local01 * halfFrac;
    }
    else
    {
        isInPill = true;

        // offset vertical mesuré depuis le centre vertical réel de la capsule
        offsetFromCenter = vec2(0.0, positionPx.y - centerY);

        // progression horizontale locale dans la partie rectiligne
        float denom = max(rectWidth, 1e-6);
        float x01 = (positionPx.x - leftCenterX) / denom;  // 0 au bord rect gauche, 1 au bord rect droit
        x01 = clamp(x01, 0.0, 1.0);

        // position le long du périmètre (ordre choisi: segment haut -> demi-droit -> segment bas -> demi-gauche)
        pxCirc01 = (offsetFromCenter.y < 0.0)
        ? (x01 * rectFrac)                                       // segment haut (g -> d)
        : (rectFrac + halfFrac + (1.0 - x01) * rectFrac);        // segment bas  (d -> g)
    }

    pxRadius01 = length(offsetFromCenter) / circleRadiusPx;

    // Initialiser la couleur du pixel
    vec4 pxColor = vec4(0.0);

    // Calculer les couleurs des différentes lignes et les fusionner
    for (int i = 0; i < NB_LST; i++)
    {
        vec4 lineColor = getLineColor(pxRadius01, pxCirc01, i, colors[i], uMinValue);
        pxColor.rgb += lineColor.rgb;
        lineColor.a *= 0.6;//Intensifi ou non le bleu
        pxColor.a += lineColor.a;
    }

    // Clamper les valeurs de couleur RGB et alpha
    pxColor.rgb = clamp(pxColor.rgb, 0.0, 1.0);
    pxColor.a = clamp(pxColor.a, 0.0, 1.0);

    //couleur blanche debut

    // Calculer l'épaisseur de la couche blanche selon l'écran entier ou non
    float whiteThickness01 = 6.0 / (circleRadiusPx / 2.0 );

    // Calculer la distance de l'effet de lumière blanche (clampée entre 0 et 1)
    float whiteDistance01 = clamp((pxRadius01 - 1.2) / whiteThickness01, 0.0, 1.0);
    float whiteOpacity = (1.0 + cos(whiteDistance01 * PI)) * 0.5;

    // Ajuster l'effet en fonction de l'état actif
    whiteOpacity *= mix(0.4, 0.8, 1.0);

    // Définir la couleur blanche (blanc pour l'instant, mais peut être ajusté pour des débogages)
    vec3 whiteOpacityColor = vec3(1.0, 1.0, 1.0);  // Changer cette couleur si nécessaire

    // Ajouter l'effet de lumière blanche à la couleur existante
    pxColor.rgb = whiteOpacityColor * whiteOpacity + (1.0 - whiteOpacity) * pxColor.rgb;

    // Ajuster l'alpha avec l'opacité de la lumière blanche
    pxColor.a = clamp(pxColor.a + whiteOpacity, 0.0, 1.0);

    //couleur blanche fin

    //colore l'interieur en noir
    float smoothThickness01 = 0.5 / circleRadiusPx;// épaisseur du lissage
    float distanceToEdge = abs(0.4 - pxRadius01);// 2 paisseur du trait: 0.2 = trop épais
    // Appliquez un anticrénelage basé sur la distance au bord
    float antialiasing = smoothstep(1.0 - smoothThickness01, 1.0, distanceToEdge);

    pxColor = mix(
        vec4(0.0, 0.0, 0.0, 1.0),  // Couleur noire opaque (arrière-plan)
        pxColor,                    // Couleur du cercle
        antialiasing                // Facteur de lissage basé sur la distance au bord
    );

    if (uEnableLineAnimation == 1)
    {
        // Paramètres pour les lignes
        float thicknessPx = 4.0;
        float lineMargin = circleRadiusPx * 0.4;
        float marginForCloseButton = 140.0;

        // Hauteur de la zone "lignes" calée sur la capsule
        float lineHeight = 2.0 * circleRadiusPx * 0.7;
        float lineYOffset = (size.y - lineHeight) * 0.5;

        // Coordonnées normalisées dans la zone lignes (0..1)
        float lineY = 1.0 - (positionPx.y - lineYOffset) / lineHeight;

        float iconRightPos = size.x - marginForCloseButton;

        if (isInPill && positionPx.x < iconRightPos && lineY > 0.0 && lineY < 1.0) {
            vec4 lineAccum = vec4(0.0);

            // Epaisseur en espace normalisé vertical
            float denomH = max(2.0 * (circleRadiusPx - lineMargin), 1e-6);
            float thickness = thicknessPx / denomH; // ~ proportion de la hauteur utile
            float intensityFactor = 1.1;

            // On utilise pxCirc01 comme "x" le long de la capsule
            float lineX = pxCirc01;

            // Normalisation sûre
            float valueDenom = max(uMaxValue - uMinValue, 1e-6);

            for (int i = 0; i < NB_LST; i++)
            {
                // --- géométrie horizontale de la portion rectiligne ---
                float r = circleRadiusPx;
                float leftEdge  = r;                         // bord rect gauche de la capsule
                float rightEdge = iconRightPos + r;          // bord rect droit (avant le bouton)
                float denomX    = max(rightEdge - leftEdge, 1e-6);
                float x01       = clamp((positionPx.x - leftEdge) / denomX, 0.0, 1.0); // 0..1 de g -> d

                // --- enveloppe qui annule l’onde aux bords (fusion des lignes) ---
                float envelope  = pow(sin(PI * x01), 3.0);//Augmenter accentuer l'annulation des sinusoiales sur les extrémités

                // --- onde voyageuse (droite -> gauche) ---
                float i01       = float(i) / float(NB_LST);

                //Changer la mutli de uTime pour ralentir ou accélerer
                float phase     = uTime * 12.0 + (x01 + i01) * 2.0 * PI; // +k*x -> mouvement vers la gauche
                float wave      = sin(phase) * envelope;

                // --- modulation par ton signal (0..1) ---
                float valueDenom = max(uMaxValue - uMinValue, 1e-6);
                float readY      = (readLineValue(x01, i) - uMinValue) / valueDenom;
                readY            = clamp(readY, 0.0, 1.0);

                // --- amplitude globale (ex : niveau sonore, 0..1) ---
                float amp = clamp(intensityFactor, 0.0, 1.0); // remplace l’ancien clamp(0.1+1.8,...)

                // --- position verticale de la ième ligne ---
                //   baseY = 0.5 -> toutes les lignes se rejoignent à 0.5 aux bords (envelope=0)
                float baseY  = 0.5;
                float sway   = 0.5;// amplitude max de l’oscillation
                float valueY = baseY + sway * (0.85 * (uRecordSoundIntensity / 10.0) * wave * amp + 0.25 * (readY - 0.5));

                float dist = abs(lineY - valueY);
                float intensity = clamp((1.0 - dist / thickness) * intensityFactor, 0.0, 1.0);
                intensity *= intensity;

                lineAccum.rgb += colors[i].rgb * intensity;
            }

            // Ajout (RGB seulement, comme ton code d’origine)
            pxColor.rgb += lineAccum.rgb;
        }
    }

    return pxColor;
}

void main()
{
    vec2 size = vec2(uWidth, uHeight);

    // uCircleRadius = rayon en pixels
    float circleRadiusPx = uCircleRadius / 1.5;

    // Position du fragment en pixels (origine bas-gauche)
    vec2 positionPx = gl_FragCoord.xy;

    if (uFullScreen == 1)
    {
        fragColor = fullScreenMode(size, positionPx, circleRadiusPx);
    }
    else
    {
        fragColor = audioPillLector(size, positionPx, circleRadiusPx);
    }
}