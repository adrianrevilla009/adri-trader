# 0001. Universo v1: ETFs UCITS de acumulación en euros (Xetra)

- **Estado:** Aceptada
- **Fecha:** 2026-11-19
- **Fuente:** [brief.md](../brief.md), «Decisiones cerradas»

## Contexto

Con 100 € y un solo desarrollador conviene minimizar fuentes de complejidad: divisas, dividendos y el sesgo de elegir acciones con el pasado a la vista. Los ETFs de EEUU como SPY o VOO están bloqueados para minoristas del EEE (PRIIPs).

## Decisión

El universo v1 son 10 a 15 ETFs UCITS de acumulación, cotizados en euros en Xetra y con 12 años o más de historia (renta variable global, EEUU, Europa, emergentes, bonos de varios plazos, oro). Se identifican por ISIN + bolsa. La lista final se limita a los que eToro ofrece como instrumentos reales, no CFD (no comprobado). Las acciones se reabren tras la fase 5.

## Consecuencias

- Sin conversión de divisa ni coste de cambio; sin dividendos que gestionar.
- El mínimo por orden de eToro (~10 $, no comprobado) con posiciones de ~25 € puede recortar el universo.
- El requisito de 12 años de historia puede excluir ETFs recientes.
