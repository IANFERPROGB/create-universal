# Create Universal

Mod de **NeoForge 1.21.1** que lleva las naves de **Create Aeronautics / Sable** al espacio: un sistema solar entero a
escala, con órbitas reales, planetas y lunas donde aterrizar, supervivencia en el vacío y todo lo necesario para viajar
entre ellos.

*An add-on for Create Aeronautics / Sable (NeoForge 1.21.1) that takes your ships to a full-scale solar system: real
orbits, landable planets and moons, survival in vacuum, navigation, autopilot and more. In Spanish and English.*

## Qué trae

- **El espacio:** se sale del Overworld subiendo hasta Y 15.000 (configurable) con la nave entera y todo lo que lleva.
  Gravedad cero, el Sol, los ocho planetas y sus lunas grandes en su sitio y moviéndose de verdad.
- **Planetas y lunas:** la Luna y Marte con el relieve real de la NASA; Mercurio, Venus y 17 lunas con relieve generado y
  sus accidentes famosos. Reentrada con fuego, cada astro con su suelo, su cielo y su aire.
- **Supervivencia:** oxígeno, traje espacial de netherita (con mejoras contra frío, radiación, calor y Venus), salas con
  oxígeno de Deep Seas, peligros de cada astro, minerales nuevos (cromita, colemanita, berilo).
- **Naves:** Estabilizador orbital (fija la nave), Estabilizador de gravedad (gravedad dentro de la nave), órbitas
  alrededor de los astros, choques destructivos con restos que caducan, entrar en el mundo dentro de tu nave.
- **Rocas:** cinturón de asteroides y anillos de Saturno con colisión, y rocas que se pueden picar.
- **Navegación:** mesa de mapa (también en grande, N×N), proyector holográfico 3D, marcadores, destino con tiempo de
  llegada, naves aparcadas y compatibilidad con los puntos de ruta de Xaero.
- **Asistente de nave:** revisa la nave (motores, combustible, energía, casco, aire), panel para los de a bordo y
  **piloto automático** (despega, gira con su giroscopio, va al destino esquivando astros y rocas, o explora).
  **Motor de crucero** para los viajes largos.
- **Vida en el espacio:** dormir en una nave (salto de media hora calculado, con alarma antes de un choque o de quedarse
  sin combustible u oxígeno), naves siempre cargadas en el espacio (10 por jugador) y avisos al dueño estés donde estés.
- **Ayuda en el juego:** descripciones con Shift y tutoriales Ponder (mantener W) de los bloques complejos.

## Requisitos

- Minecraft 1.21.1 con NeoForge 21.1.
- Create 6.0.10, Sable 2.0.6, Create Aeronautics 1.3.2 y Create Deep Seas (create_submarine) 3.3.
- Cloth Config (lo usa Gravity Control, que va dentro del mod).
- Recomendados: Create Propulsion (el piloto automático maneja sus propulsores), Xaero's Minimap y World Map.
- No es compatible con otros mods de espacio o dimensiones de Sable (Ad Astra, Stellaris, Galacticraft, Rocketnautics,
  Dimensional Sable...).

## Configuración

`config/create_universal-common.toml`: altura de salida al espacio, duración de los restos, rocas (distancia de colisión,
cantidad, densidad, giro de los anillos), naves cargadas por jugador, velocidad a partir de la que no se cargan chunks en
el espacio, dormir (minutos y espera) y fuerza del motor de crucero.

## Créditos

- Hecho por superian.
- Lleva dentro **Gravity Changer / Gravity Control** de Tfarcenim (licencia MIT).
- Relieve de la Luna y de Marte: datos de la NASA (LOLA, MOLA). Texturas de los astros: NASA.
- Gracias a los autores de Create, Sable, Create Aeronautics, Create Propulsion y Create Deep Seas.

## Para desarrollar

Proyecto de NeoForge con ModDevGradle. Los jars de los mods con los que se compila van en `libs/` (no se suben al
repositorio). Compilar: `./gradlew build --offline`.
