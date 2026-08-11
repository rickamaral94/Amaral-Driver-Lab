# Auditoria histórica da identidade do driver

> Relatório determinístico e somente leitura. Ele reprocessa apenas evidências já
> sanitizadas e publicadas; nenhuma Issue é alterada ou republicada.

## Resumo

| Classificação | Issues |
|---|---:|
| Rótulo correto | 5 |
| Rótulo errado | 2 |
| Não verificável | 1 |
| **Total** | **8** |

Política aplicada: `driver_identity_policy_version = 1`.

## Divergências

| Issue | Rótulo publicado | Identidade recalculada | Confiança | Classificação |
|---:|---|---|---|---|
| [#27](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/27) | Turnip (Mesa Freedreno) | `turnip` | `runtime_confirmed` | **correto** |
| [#28](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/28) | Unknown | `unknown` | `inferred` | **não verificável** |
| [#29](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/29) | Turnip Adreno (TM) 740 | `turnip` | `runtime_confirmed` | **correto** |
| [#31](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/31) | Turnip (Mesa Freedreno) | `turnip` | `runtime_confirmed` | **correto** |
| [#32](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/32) | Turnip (Mesa Freedreno) | `qualcomm_blob` | `runtime_confirmed` | **errado** |
| [#39](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/39) | Turnip (Mesa Freedreno) | `turnip` | `runtime_confirmed` | **correto** |
| [#40](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/40) | Qualcomm Technologies Inc. Adreno Vulkan Driver | `qualcomm_blob` | `runtime_confirmed` | **correto** |
| [#43](https://github.com/rickamaral94/Amaral-Driver-Lab/issues/43) | turnip Mesa driver 26.2.99 | `qualcomm_blob` | `runtime_confirmed` | **errado** |

## Evidências sanitizadas usadas

### Issue #27

Resultado: `turnip` / `runtime_confirmed` / **correto**.

- `L4: Driver: Turnip (Mesa Freedreno)`
- `L9: Driver Name: turnip Mesa driver`

### Issue #28

Resultado: `unknown` / `inferred` / **não verificável**.

- `L11: Driver Path : /vendor/lib64/egl/libGLESv2_adreno.so`
- `L12: Driver Version : 0676.53`
- `L3: AdrenoGLES-0: QUALCOMM build : 69e13475cb`

### Issue #29

Resultado: `turnip` / `runtime_confirmed` / **correto**.

- `L19: Using GPU: Turnip Adreno (TM) 740`

### Issue #31

Resultado: `turnip` / `runtime_confirmed` / **correto**.

- `L4: Driver: Turnip (Mesa Freedreno)`
- `L9: Driver Name: turnip Mesa driver`

### Issue #32

Resultado: `qualcomm_blob` / `runtime_confirmed` / **errado**.

- `L15: Vulkan Driver: - 512.676.53`
- `L53: Device: Disabling shader float controls and 64-bit integer features on Qualcomm proprietary drivers`
- `L58: [GPU Logging] Initialized with level: Standard, driver: Qualcomm Proprietary`

### Issue #39

Resultado: `turnip` / `runtime_confirmed` / **correto**.

- `L4: Driver: Turnip (Mesa Freedreno)`
- `L9: Driver Name: turnip Mesa driver`

### Issue #40

Resultado: `qualcomm_blob` / `runtime_confirmed` / **correto**.

- `L4: Driver: Qualcomm Proprietary`
- `L9: Driver Name: Qualcomm Technologies Inc. Adreno Vulkan Driver`

### Issue #43

Resultado: `qualcomm_blob` / `runtime_confirmed` / **errado**.

- `L15: Vulkan Driver: - 512.676.53`
- `L44: Device: Disabling shader float controls and 64-bit integer features on Qualcomm proprietary drivers`
- `L49: [GPU Logging] Initialized with level: Standard, driver: Qualcomm Proprietary`

## Limites

- A auditoria usa somente os trechos sanitizados preservados nas Issues; linhas omitidas não são inferidas.
- Versão no formato do blob sem vendor explícito gera `unknown` / `disputed`, nunca veto Qualcomm.
- `unknown`, `disputed` e relatórios v1 `unaudited` ficam fora de agregações por driver.
- O comando apenas escreve este Markdown; ele não usa a API de escrita do GitHub.

Gerado por `tools/audit-driver-identities.py` a partir de
`tools/driver-identity-audit-input.json`.
