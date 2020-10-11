# PuzzleSolver
Puzzle Solver currently capable of solving torus puzzles automatically.

### Implementation
It uses OCR to analyze the puzzle, then uses Accessibility Services to automatically solve it. 

The core solving algorithm is based on a combination of two submissions ([1](https://codegolf.stackexchange.com/a/172907/98567), [2](https://codegolf.stackexchange.com/a/172852/98567)) I found on Code Golf, with some extra help from [this paper](https://www.mdpi.com/1999-4893/5/1/18).

The runtime should be linear, but the moves generated are not guarenteed to be optimal (unlike traditional searchable problems like 8-puzzle for example), as a result, the actual moving part takes the longest time. 

### Performance
In my experience the app on average solves a 6x6 torus puzzle in about 2 seconds, although the entire process of recognizing then solving the puzzle takes longer.

### Supported Environments
So far, only tested on [Exponential Idle](https://play.google.com/store/apps/details?id=com.conicgames.exponentialidle), but theoritically as long as the puzzles are number based and clearly identifiable, they should be solvable with this app.

### Roadmap?
This app is a simple quick "hack" that I put together in a short time (as you can see from the spaghetti code). But there are some potential improvements that could be implemented in the future:

- [ ] Improve "farming"/continuous mode with Accessibility Service stuff
- [ ] Improve OCR performance
- [ ] More puzzle support (e.g. IDA* for 15-puzzles)
- [ ] Better UI

In addition, feel free to contribute ideas or implementations with PRs or Issues.
