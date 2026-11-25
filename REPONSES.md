# Réponses aux questions du TP

## Partie 0 - Mise en place

### Q0.1 Pourquoi interdit-on de pousser directement sur main ?

1. **Garder main toujours stable.** main est la version de référence : celle qu'on tague, qu'on livre et à partir de laquelle on crée les branches. Un push direct peut y mettre du code qui ne compile pas ou qui casse les tests, et tout le monde repart ensuite d'une base cassée.
2. **Travail en binôme : chaque modification est relue.** Avec une Pull Request obligatoire et une approbation, le binôme voit et teste le code avant la fusion. Il repère les erreurs, comprend ce qui a changé et reste au courant de tout le projet. Cela évite aussi qu'un push de l'un écrase ou casse le travail de l'autre sans prévenir.

En plus, l'historique de main ne contient que des fusions de PR : chaque changement est documenté (ce qui change, comment le tester) et facile à retrouver ou à annuler.

### Q0.2 La collection Bruno est faite de fichiers texte rangés dans le dépôt. Qu'est-ce que cela change pour la relecture d'une Pull Request ?

- **Les tests se relisent comme le code.** Une requête ajoutée ou modifiée apparaît dans le diff de la PR, ligne par ligne : on voit l'URL, le corps envoyé et le code attendu. Le relecteur vérifie que les tests correspondent bien à ce que la PR change (par exemple, une nouvelle route a sa requête et son test).
- **Le binôme peut tester sans rien installer de plus.** Il récupère la branche de la PR, ouvre le dossier `bruno/` et lance la collection : il a exactement les mêmes requêtes que l'auteur, dans la même version que le code. Pas d'export à s'envoyer, pas de collection qui diverge d'un poste à l'autre.
- **On repère une régression.** Si une PR modifie ou supprime un test existant (un code attendu qui passe de 201 à 200, par exemple), cela se voit dans le diff et doit être justifié.
