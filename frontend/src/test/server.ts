import { setupServer } from 'msw/node'

// Individual tests supply handlers so missing mocks always fail.
export const server = setupServer()
