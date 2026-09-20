// Demo sample for Aegis Debug — see ../DEMO.md.
// Demonstrates AEG-NULL-001: `user` is initialized to null and dereferenced with no null
// check nearby.
export function Greeting() {
  let user = null

  return <span>Hello, {user.name}</span>
}
