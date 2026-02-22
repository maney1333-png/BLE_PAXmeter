VORWORT
Diese App wurde im Rahmen einer Seminar-Arbeit für die Uni entwickelt. 
Sie sucht die Umgebung nach Bluetooth-Signalen ab, die von diversen Geräten in Betriebsbereitschaft abgegeben werden - sogenannte "Advertising-Pakete". Diese enthalten keine persönlichen Daten und keine Informationen zu übertragenen Daten - sogenannter "Payload". Zudem wechseln die Sender spätestens nach 15 Minuten ihre Kennung automatisch, um diese Signale zu anonymisieren.
Die App erfasst alle gesichteten MAC-Adressen mit deren Anzahl, deren jeweilige Signalstärke, Zeitstempel und dazu noch die per GPS gemessene Geschwindigkeit. 

I. VOR DER MESSUNG
1) Fahrt eintragen
a) Via GPS -> HALT SUCHEN -> Abfrage an VVO-EFA via GPS
b) Manuelle Einträge von Linie, Richtung, etc.
2) Filter einstellen
3) (NOCH NICHT!) -> MESSUNG STARTEN

II. WÄHREND DER MESSUNG
1) Jetzt erst MESSUNG STARTEN, wenn die Türen zu sind (sonst geht der Scanner zu früh in den Stromspar-Modus)
2) Fahrgäste zählen und Gesamtzahl in der Mitte eintragen (oder Einsteiger bei +, Aussteiger bei -)
3) Messanzeigen beobachten!
4) Nach Ankunft an Haltestelle -> CUT drücken, aber erst, wenn die Türen zu sind
5) Abschnitt wurde gespeichert, Dateneingabe folgt für nächsten Abschnitt, Abfrage im Hintergrund an VVO-EFA zur Ermittlung der Haltestelle
6) Ab Schritt 2) beliebig oft wiederholen
7) Nach Ankunft am letzten Halt -> CUT drücken und dann erst -> MESSUNG BEENDEN
8) Dialogfeld mit Freitext beachten

III. AUSWERTUNG
Die App speichert alle gefilterten Werte in einer JSON. Zur Kontrolle gibt es eine Echtzeitanzeige.

Die App dient primär zur Datenerhebung für weitere Forschungen. Prognosen sind hiermit nur experimentell möglich. 
