# First Steps: Programming for Absolute Beginners

Welcome! If you have never written a line of code before, you are in the right place. 

## What is Programming?

At its core, programming is just giving a computer a list of instructions. A **script** (like a `.nox` file) is like a recipe. You tell the computer exactly what to do, step by step, and it follows those steps perfectly.

In NSL (Nox Scripting Language), we write these instructions in plain text.

## Variables (Boxes for Data)

Think of a **variable** as a labeled box where you can store information. When you create a box, you have to tell the computer what *kind* of information belongs inside. This is called a **type**.

In NSL, here are the two most common types:
1. `int` (Integer): Used for whole numbers.
2. `string`: Used for text.

```c
// We create a box named 'age' for an integer, and put 10 in it.
int age = 10;

// We create a box named 'name' for text, and put "Alice" in it.
string name = "Alice";
```

### Mixing Text and Variables

Often, you want to print out a message that includes your variables. In NSL, we do this using **backticks** (`` ` ``) and the `${}` symbol.

```c
string greeting = `Hello there, ${name}! You are ${age} years old.`;
// The computer sees: "Hello there, Alice! You are 10 years old."
```

## Control Flow (Making Decisions)

Sometimes, you only want the computer to do something *if* a certain condition is true. We use `if` and `else` for this, just like in real life ("If it is raining, take an umbrella, else wear sunglasses").

```c
int temperature = 20;

if (temperature > 25) {
    yield "It is hot outside!";
} else if (temperature < 15) {
    yield "It is cold outside!";
} else {
    yield "The weather is perfect.";
}
```

*(Note: `yield` is how we tell NSL to print a message to the screen!)*

## Loops (Doing Things Repeatedly)

Computers are great at doing repetitive tasks very fast. If you want to do something 5 times, you use a **loop**.

### The `while` Loop
A `while` loop keeps running as long as a condition is true.

```c
int count = 1;

while (count <= 3) {
    yield `Count is ${count}`;
    count = count + 1; // Add 1 to count
}
// This will print:
// Count is 1
// Count is 2
// Count is 3
```

### The `for` Loop
A `for` loop is a shortcut for counting.

```c
for (int i = 1; i <= 3; i++) {
    yield `Count is ${i}`;
}
```
*(This does exactly the same thing as the `while` loop above!)*

## Functions (Recipes)

A **function** is a mini-program or recipe. You give it some inputs (ingredients), it does some work, and it gives you an output (the finished dish).

Here is a function that takes two numbers and adds them together:

```c
// 'int' at the start means this recipe returns an integer.
int addNumbers(int a, int b) {
    int total = a + b;
    return total; // Give the answer back
}
```

Now you can use this recipe anywhere:
```c
int result = addNumbers(5, 10); // result is now 15
```

## Your First Program

Every NSL script must have a special function called `main`. This is the starting point—the front door of your program. When you run your script, the computer looks for `main()` and starts reading instructions from there.

Let's put it all together:

```c
// A helper function
int multiply(int a, int b) {
    return a * b;
}

// The front door of your program
main(string myName) {
    yield `Welcome to NSL, ${myName}!`;
    
    int mathResult = multiply(4, 5);
    yield `4 times 5 is ${mathResult}`;
    
    return "Finished!"; // Programs end when main returns
}
```

Great work! You now understand variables, conditions, loops, and functions.  Let's, head over to [Getting Started](getting-started.md) to learn how to actually run this code on your computer.
