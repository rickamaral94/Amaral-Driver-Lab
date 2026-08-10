package com.amaral.driverlab;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * Keeps human-readable result logs bilingual without changing technical JSON contracts.
 * Portuguese app locales render Portuguese; every other app locale renders English.
 */
final class ReportLanguage {
    private ReportLanguage() {}

    static boolean isPortuguese(Context context) {
        return isPortuguese(LanguageManager.effectiveLanguageTag(context));
    }

    static String languageTag(Context context) {
        return isPortuguese(context) ? "pt-BR" : "en";
    }

    static Context wrap(Context context) {
        Locale locale = Locale.forLanguageTag(languageTag(context));
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration());
        configuration.setLocale(locale);
        configuration.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(configuration);
    }

    static boolean isPortuguese(String languageTag) {
        if (languageTag == null || languageTag.trim().isEmpty()) return false;
        return "pt".equals(Locale.forLanguageTag(languageTag).getLanguage());
    }

    static String humanText(Context context, String portugueseText) {
        return humanText(LanguageManager.effectiveLanguageTag(context), portugueseText);
    }

    static String humanText(String languageTag, String portugueseText) {
        if (portugueseText == null || isPortuguese(languageTag)) return portugueseText;
        String output = portugueseText;
        // Long and dynamic fragments must be replaced before their shorter components.
        for (String[] translation : ENGLISH_TRANSLATIONS) {
            output = output.replace(translation[0], translation[1]);
        }
        return output;
    }

    static String limitation(Context context, String portugueseText) {
        return limitation(LanguageManager.effectiveLanguageTag(context), portugueseText);
    }

    static String limitation(String languageTag, String portugueseText) {
        if (isPortuguese(languageTag)) return portugueseText;
        return "Results are valid only for the same profile, hardware, driver identity, "
                + "settings, and environment. This benchmark does not reproduce a complete "
                + "game workload and does not prove performance in every emulator or title.";
    }

    private static final String[][] ENGLISH_TRANSLATIONS = {
            {"Falhas de compatibilidade, validade ou cobertura impediram uma recomendação.",
                    "Compatibility, validity, or coverage failures prevented a recommendation."},
            {"A diferença geral entre ", "The overall difference between "},
            {" ficou dentro da margem prática de ±", " was within the practical margin of ±"},
            {" apresentou resultado geral inferior a ", " had a lower overall result than "},
            {" foi superior a ", " outperformed "},
            {" no perfil de validação v", " in validation profile v"},

            {"### Identidade dos drivers — não confundir os papéis", "### Driver identity — do not confuse the roles"},
            {"**DRIVER CANDIDATO:** versão nova ou experimental que está sendo avaliada.",
                    "**CANDIDATE DRIVER:** new or experimental version under evaluation."},
            {"**DRIVER DE REFERÊNCIA:** baseline usado para medir se o candidato melhorou ou piorou.",
                    "**REFERENCE DRIVER:** baseline used to measure whether the candidate improved or regressed."},
            {"### Placar geral da comparação", "### Overall comparison scorecard"},
            {"### Comparativo principal por etapa", "### Main comparison by step"},
            {"### Estatística detalhada por etapa", "### Detailed statistics by step"},
            {"### Auditoria dos drivers carregados", "### Loaded-driver audit"},
            {"### O que melhorou e o que piorou", "### What improved and what regressed"},
            {"### Alvo de hardware", "### Hardware target"},
            {"### Resumo da execução", "### Execution summary"},
            {"### Bloqueios e ressalvas", "### Blockers and caveats"},
            {"### Etapas com falha", "### Failed steps"},
            {"### Validade", "### Validity"},
            {"### Diff de capacidades", "### Capability diff"},

            {"Cena avançada: GPU Stress 3D 720p", "Advanced scene: GPU Stress 3D 720p"},
            {"Cena visível: materiais e amostragem", "Visible scene: materials and sampling"},
            {"Cena visível: materiais procedurais", "Visible scene: procedural materials"},
            {"Cena visível: geometria e depth", "Visible scene: geometry and depth"},
            {"Cena visível: pós-processamento", "Visible scene: post-processing"},
            {"Correção offscreen após carga", "Offscreen correctness after load"},
            {"Correção offscreen inicial", "Initial offscreen correctness"},
            {"Compilação de shaders", "Shader compilation"},
            {"Frametime da cena estável", "Stable-scene frame time"},
            {"Trace gráfico, compute e barreiras", "Graphics, compute, and barrier trace"},
            {"Trace gráfico/compute/barreiras", "Graphics/compute/barrier trace"},

            {"Driver candidato recomendado", "Candidate driver recommended"},
            {"Driver de referência recomendado", "Reference driver recommended"},
            {"Driver do sistema recomendado", "System driver recommended"},
            {"Driver candidato não recomendado", "Candidate driver not recommended"},
            {"Driver do sistema Android", "Android system driver"},
            {"driver do sistema", "system driver"},
            {"Driver candidato", "Candidate driver"},
            {"Driver de referência", "Reference driver"},
            {"Empate técnico", "Technical tie"},

            {"| Papel no teste | Pacote carregado | Loader | SHA-256 completo |",
                    "| Test role | Loaded package | Loader | Full SHA-256 |"},
            {"| Etapa | Melhor valor | DRIVER DE REFERÊNCIA (mediana) | DRIVER CANDIDATO (mediana) | Diferença absoluta | Delta normalizado | Vencedor | IC 95% |",
                    "| Step | Better value | REFERENCE DRIVER (median) | CANDIDATE DRIVER (median) | Absolute difference | Normalized delta | Winner | 95% CI |"},
            {"| Estatística | DRIVER DE REFERÊNCIA | DRIVER CANDIDATO | Diferença cand-ref |",
                    "| Statistic | REFERENCE DRIVER | CANDIDATE DRIVER | Candidate-reference difference |"},
            {"| Valor | DRIVER DE REFERÊNCIA | DRIVER CANDIDATO | Diferença |",
                    "| Value | REFERENCE DRIVER | CANDIDATE DRIVER | Difference |"},
            {"| Etapa | Rodada | Papel | Loader | Pacote | SHA-256 | Sucesso |",
                    "| Step | Round | Role | Loader | Package | SHA-256 | Success |"},
            {"| Indicador | Resultado |", "| Indicator | Result |"},
            {"| Campo | Valor |", "| Field | Value |"},

            {"Vitórias do DRIVER CANDIDATO", "CANDIDATE DRIVER wins"},
            {"Vitórias do DRIVER DE REFERÊNCIA", "REFERENCE DRIVER wins"},
            {"Rodadas candidato / empate / referência", "Candidate / tie / reference rounds"},
            {"Etapas de performance", "Performance steps"},
            {"Empates técnicos", "Technical ties"},
            {"Etapas inconclusivas", "Inconclusive steps"},
            {"Delta médio do candidato", "Mean candidate delta"},
            {"Delta mediano do candidato", "Median candidate delta"},
            {"Pares estatísticos válidos", "Valid statistical pairs"},
            {"Vencedor por etapas", "Winner by steps"},
            {"Fabricante / modelo", "Manufacturer / model"},
            {"Console/dispositivo", "Console/device"},

            {"Etapas concluídas/falhas", "Completed/failed steps"},
            {"Índice de compatibilidade", "Compatibility index"},
            {"Índice de performance", "Performance index"},
            {"Índice geral", "Overall index"},
            {"Compatibilidade", "Compatibility"},
            {"Ganho ponderado", "Weighted gain"},
            {"Comparação", "Comparison"},
            {"Confiança", "Confidence"},
            {"Estado", "State"},
            {"Modo", "Mode"},

            {"Blocos divergentes máximos", "Maximum divergent blocks"},
            {"Máx. blocos divergentes", "Max divergent blocks"},
            {"Comparações visuais", "Visual comparisons"},
            {"Coeficiente de variação", "Coefficient of variation"},
            {"Razão candidato/referência", "Candidate/reference ratio"},
            {"Melhoria normalizada do candidato", "Normalized candidate improvement"},
            {"Rodadas vencidas — candidato / empate / referência", "Rounds won — candidate / tie / reference"},
            {"Intervalo de confiança de 95%", "95% confidence interval"},
            {"Classificação", "Classification"},
            {"Diferença absoluta", "Absolute difference"},
            {"Delta normalizado", "Normalized delta"},
            {"mais próximo de 100%", "closest to 100%"},
            {"Amostras", "Samples"},
            {"Mediana", "Median"},
            {"Média", "Mean"},
            {"menor", "lower"},
            {"maior", "higher"},
            {"unidade nativa", "native unit"},
            {"delta normalizado", "normalized delta"},

            {"Melhor resultado", "Best result"},
            {"Pior resultado", "Worst result"},
            {"Resultado inconclusivo", "Inconclusive result"},
            {"**Resultado**", "**Result**"},
            {"Sem métricas consolidadas", "No consolidated metrics"},
            {"Sem alertas automáticos", "No automatic warnings"},
            {"Sem resumo", "No summary"},
            {"Nenhuma.", "None."},
            {"falha sem detalhe", "failure without details"},

            {"Placar por etapas: CANDIDATO ", "Score by steps: CANDIDATE "},
            {" REFERÊNCIA", " REFERENCE"},
            {"Vencedor: ", "Winner: "},
            {"REFERÊNCIA — mediana ", "REFERENCE — median "},
            {"CANDIDATO — mediana ", "CANDIDATE — median "},
            {"REFERÊNCIA: ", "REFERENCE: "},
            {"CANDIDATO: ", "CANDIDATE: "},
            {"Diferença: ", "Difference: "},
            {"Blocos divergentes: ", "Divergent blocks: "},
            {" · comparações: ", " · comparisons: "},
            {", média ", ", mean "},
            {" · razão cand/ref: ", " · candidate/reference ratio: "},
            {" · amostras ref/cand: ", " · reference/candidate samples: "},
            {" · pares: ", " · pairs: "},
            {" · empates ", " · ties "},
            {" · inconclusivas ", " · inconclusive "},
            {"Chave: ", "Key: "},

            {"EMPATE TÉCNICO", "TECHNICAL TIE"},
            {"INCONCLUSIVO", "INCONCLUSIVE"},
            {"DRIVER CANDIDATO", "CANDIDATE DRIVER"},
            {"DRIVER DE REFERÊNCIA", "REFERENCE DRIVER"},
            {"CANDIDATO", "CANDIDATE"},
            {"REFERÊNCIA", "REFERENCE"},

            {"Aparelho", "Device"},
            {"Candidato", "Candidate"},
            {"Método", "Method"},
            {"rodada(s), ordem", "round(s), order"},
            {"Veredito", "Verdict"},
            {"Pixels compatíveis", "Matching pixels"},
            {"Delta candidato × sistema", "Candidate × system delta"},
            {"Fases com falha", "Failed phases"},
            {"Extensões ganhas/perdidas", "Extensions gained/lost"},
            {"Eventos de falha", "Failure events"},
            {"Diff de capacidades", "Capability diff"},
            {"Extensões ganhas", "Extensions gained"},
            {"Extensões perdidas", "Extensions lost"},
            {"Features ganhas", "Features gained"},
            {"Features perdidas", "Features lost"},
            {"Resultado JSON", "Result JSON"},
            {"parcial", "partial"},

            {"_Gerado por Amaral Driver Lab ", "_Generated by Amaral Driver Lab "},
            {"Sistema Android", "Android system"},
            {" e ", " and "},
            {"Título da issue vazio", "Empty issue title"},
            {"Corpo da issue vazio", "Empty issue body"},
            {"Área de transferência indisponível", "Clipboard unavailable"},
            {"Owner/repositório do GitHub inválido", "Invalid GitHub owner/repository"},
            {" | sim |", " | yes |"},
            {" | não |", " | no |"}
    };
}
