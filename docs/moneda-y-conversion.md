# Moneda y conversión USD/EUR en eToro

> Estado: **no comprobado en la cuenta real**. Resumen de la documentación pública de eToro (consultada 2026-11-27) y de lo que
> vimos en la demo. Antes de usar dinero real hay que contrastarlo con la cuenta del usuario.

## Lo que sabemos

- **Demo:** la cuenta demo está en USD. El bot la trata 1 USD = 1 EUR, así que los importes de la demo no reflejan ningún cambio de moneda.
- **Cuenta real (según eToro):** todo cliente puede tener una cuenta en USD; los clientes de la UE elegibles pueden tener además una
  **cuenta en EUR**. Ver [comisiones de conversión](https://www.etoro.com/trading/fees/conversion) y
  [cuentas en moneda local](https://www.etoro.com/trading/currency-accounts/).
- **Mover dinero entre la cuenta EUR y la USD:** 0,75 % para el nivel estándar (menos en niveles superiores).
- **Ingresos en otra moneda a una cuenta USD:** conversión del 1,5 % o 3 % según moneda y método de pago.
- **ETF en EUR (Xetra) con cuenta EUR:** en principio no hace falta convertir. Las fuentes consultadas no coinciden del todo en
  si se cobra conversión en ETF; **hay que verlo en la página de comisiones de la región del usuario.**

## Por qué importa al bot

1. El libro de cuentas deriva unidades como importe / precio, así que el broker debe informar del precio en la **moneda de la cuenta**
   (ya corregido para USD en la demo, PR #74). Con una cuenta EUR habría que repetir esa comprobación.
2. Con 100 € un 0,75 % son 0,75 €: no es grande, pero se come parte de la ventaja de cualquier estrategia. Debe entrar en el
   modelo de costes del backtest (hoy: 5 pb de spread y 2 de deslizamiento, valores propios sin contrastar).
3. Si la cuenta real fuese solo USD, el cambio EUR→USD ocurriría al ingresar y de nuevo al retirar, y habría riesgo de cambio.

## Pendiente antes de dinero real

- [ ] Ver si la cuenta real puede abrirse como cuenta en EUR y confirmar la comisión exacta.
- [ ] Decidir si el modelo de costes incluye la conversión (ADR nueva si cambia).
- [ ] Probar una orden real mínima y revisar el precio y la moneda que devuelve la API.
