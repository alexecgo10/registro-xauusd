# Registro XAUUSD

App personal para llevar el registro diario de las cuentas de trading de oro (XAUUSD).

- **Calendario** de resultados por cuenta (Principal, Secundaria o ambas).
- **Resumen**: balance, % acumulado compuesto, drawdown, media diaria y gráficos.
- **Copiers**: reparto diario a Deyvid y Franco, comisión del 30 % y calendario propio.
- **Registro**: importación del informe de MT5 (Historial → Informe → Open XML), días a mano y copias de seguridad.
- **Expectativa**: proyección del balance con la media diaria.

## Cómo funciona

- Web estática (HTML + JS, sin compilación) publicada con GitHub Pages.
- Datos en **Firebase Firestore**, bajo `users/{uid}/…`, con caché local para usarla sin conexión.
- Acceso con **Google**. Las reglas (`firestore.rules`) solo dejan a cada usuario leer y escribir lo suyo.
- Instalable como app (PWA): en Chrome, menú ⋮ → *Instalar app*.

La configuración de Firebase en `app.js` es pública por diseño; la seguridad la dan las reglas de Firestore.

## Próximas fases

1. APK de Android generado con GitHub Actions.
2. Lectura de las notificaciones de LIFT.SIGNALS y notificación fija con la señal abierta.
