export function randomIntBetween(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

export function uuidv4() {
  return crypto.randomUUID();
}
