# FakePlayers (Paper 1.21.11 + Velocity)

Build:
  cd paper    && gradle build   -> build/libs/FakePlayers-Paper.jar    (Java 21)
  cd velocity && gradle build   -> build/libs/FakePlayers-Velocity.jar (Java 17+)

Setup:
  1. Put the jars in each server's / the proxy's plugins folder.
  2. Set the SAME secret in paper config.yml and velocity plugins/fakeplayers/config.properties.
  3. Backend server names are taken from velocity.toml automatically.

Paper: /fp add|remove|list|info|tp|set|rename|skin|inv|patrol|say|cmd|action|respawn|save|reload
Velocity: /vfp list, /fglist, /vfp run <server> <fp command>
