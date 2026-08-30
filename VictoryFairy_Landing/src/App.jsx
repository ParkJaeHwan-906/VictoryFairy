import Nav from './components/Nav.jsx';
import Hero from './components/Hero.jsx';
import Problem from './components/Problem.jsx';
import QuizSection from './components/QuizSection.jsx';
import BQSection from './components/BQSection.jsx';
import LoungeSection from './components/LoungeSection.jsx';
import CharacterSection from './components/CharacterSection.jsx';
import Download from './components/Download.jsx';
import Footer from './components/Footer.jsx';

export default function App() {
  return (
    <>
      <Nav />
      <main id="top">
        <Hero />
        <Problem />
        <QuizSection />
        <BQSection />
        <LoungeSection />
        <CharacterSection />
        <Download />
      </main>
      <Footer />
    </>
  );
}
