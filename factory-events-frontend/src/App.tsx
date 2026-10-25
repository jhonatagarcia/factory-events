import { useState } from 'react'
import { EventTable } from './components/EventTable'
import { EventForm } from './components/EventForm'

import './App.css'

function App() {
  const [listRevision, setListRevision] = useState(0)
  return (
    <div className="app-shell">
      <main id="main-content" className="page-content">
        <header className="page-heading">
          <p className="eyebrow">REGISTRO DE OCORRÊNCIAS</p>
          <h1>Eventos da fábrica</h1>
          <p className="page-description">
            Registre o que acontece na operação, com clareza e rastreabilidade.
          </p>
        </header>

        <div className="workspace">
          <EventForm onCreated={() => setListRevision((current) => current + 1)} />
          <EventTable revision={listRevision} />
        </div>

        <footer className="page-footer">Eventos da fábrica <span aria-hidden="true">·</span> Produção e qualidade em um só lugar.</footer>
      </main>
    </div>
  )
}

export default App
